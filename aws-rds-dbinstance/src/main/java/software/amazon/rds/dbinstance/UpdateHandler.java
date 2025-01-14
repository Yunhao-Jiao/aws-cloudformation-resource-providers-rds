package software.amazon.rds.dbinstance;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;

import com.amazonaws.util.CollectionUtils;
import com.amazonaws.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBParameterGroup;
import software.amazon.awssdk.services.rds.model.DBParameterGroupStatus;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DescribeDbEngineVersionsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbParameterGroupsResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.rds.common.handler.Commons;
import software.amazon.rds.common.handler.HandlerConfig;
import software.amazon.rds.dbinstance.util.ImmutabilityHelper;

public class UpdateHandler extends BaseHandlerStd {

    public static final String PENDING_REBOOT_STATUS = "pending-reboot";

    public UpdateHandler() {
        this(HandlerConfig.builder().probingEnabled(true).build());
    }

    public UpdateHandler(final HandlerConfig config) {
        super(config);
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final Logger logger
    ) {
        if (!ImmutabilityHelper.isChangeMutable(request.getPreviousResourceState(), request.getDesiredResourceState())) {
            return ProgressEvent.failed(
                    request.getDesiredResourceState(),
                    callbackContext,
                    HandlerErrorCode.NotUpdatable,
                    "Resource is immutable"
            );
        }

        final Collection<Tag> previousTags = Translator.translateTagsFromRequest(
                mergeMaps(Arrays.asList(
                        request.getPreviousSystemTags(),
                        request.getPreviousResourceTags()
                ))
        );
        final Collection<Tag> desiredTags = Translator.translateTagsFromRequest(
                mergeMaps(Arrays.asList(
                        request.getSystemTags(),
                        request.getDesiredResourceTags()
                ))
        );

        final Collection<DBInstanceRole> previousRoles = request.getPreviousResourceState().getAssociatedRoles();
        final Collection<DBInstanceRole> desiredRoles = request.getDesiredResourceState().getAssociatedRoles();

        return ProgressEvent.progress(request.getDesiredResourceState(), callbackContext)
                .then(progress -> {
                    if (shouldSetParameterGroupName(request)) {
                        return setParameterGroupName(rdsProxyClient, progress);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldSetDefaultVpcId(request)) {
                        return setDefaultVpcId(rdsProxyClient, ec2ProxyClient, progress);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldUnsetMaxAllocatedStorage(request)) {
                        return unsetMaxAllocatedStorage(rdsProxyClient, request, progress);
                    }
                    return progress;
                })
                .then(progress -> ensureEngineSet(rdsProxyClient, progress))
                .then(progress -> execOnce(progress, () ->
                                updateDbInstance(proxy, request, rdsProxyClient, progress),
                        CallbackContext::isUpdated, CallbackContext::setUpdated)
                )
                .then(progress -> execOnce(progress, () -> {
                            if (shouldReboot(rdsProxyClient, progress)) {
                                return rebootAwait(proxy, rdsProxyClient, progress);
                            }
                            return progress;
                        }, CallbackContext::isRebooted, CallbackContext::setRebooted)
                )
                .then(progress -> execOnce(progress, () ->
                                updateAssociatedRoles(proxy, rdsProxyClient, progress, previousRoles, desiredRoles),
                        CallbackContext::isUpdatedRoles, CallbackContext::setUpdatedRoles)
                )
                .then(progress -> updateTags(proxy, rdsProxyClient, progress, previousTags, desiredTags))
                .then(progress -> new ReadHandler().handleRequest(proxy, request, callbackContext, rdsProxyClient, ec2ProxyClient, logger));
    }

    private boolean shouldReboot(
            final ProxyClient<RdsClient> proxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        try {
            final DBInstance dbInstance = fetchDBInstance(proxyClient, progress.getResourceModel());
            Optional<DBParameterGroupStatus> maybeStatus = dbInstance.dbParameterGroups().stream().findFirst();
            if (maybeStatus.isPresent()) {
                return PENDING_REBOOT_STATUS.equals(maybeStatus.get().parameterApplyStatus());
            }
        } catch (DbInstanceNotFoundException e) {
            return false;
        }
        return false;
    }

    private boolean shouldSetParameterGroupName(final ResourceHandlerRequest<ResourceModel> request) {
        final ResourceModel desiredModel = request.getDesiredResourceState();
        final ResourceModel previousModel = request.getPreviousResourceState();

        return ObjectUtils.notEqual(desiredModel.getDBParameterGroupName(), previousModel.getDBParameterGroupName()) &&
                ObjectUtils.notEqual(desiredModel.getEngineVersion(), previousModel.getEngineVersion()) &&
                BooleanUtils.isTrue(request.getRollback());
    }

    private ProgressEvent<ResourceModel, CallbackContext> updateDbInstance(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::modify-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(resourceModel -> Translator.modifyDbInstanceRequest(
                        request.getPreviousResourceState(),
                        request.getDesiredResourceState(),
                        BooleanUtils.isTrue(request.getRollback()))
                )
                .backoffDelay(config.getBackoff())
                .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        modifyRequest,
                        proxyInvocation.client()::modifyDBInstance
                ))
                .stabilize((modifyRequest, response, proxyInvocation, model, context) -> withProbing(
                        context,
                        "update-db-instance-available",
                        3,
                        () -> isDbInstanceStabilized(proxyInvocation, model)
                ))
                .handleError((modifyRequest, exception, client, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        MODIFY_DB_INSTANCE_ERROR_RULE_SET
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> unsetMaxAllocatedStorage(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceHandlerRequest<ResourceModel> request,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        // In order to disable an instance autoscaling, `MaxAllocatedStorage` property has to be unset.
        // The only way to unset `MaxAllocatedStorage` is to set it to `AllocatedStorage` value upon an update.
        // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIOPS.StorageTypes.html#USER_PIOPS.Autoscaling
        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, request.getDesiredResourceState());
            request.getDesiredResourceState().setMaxAllocatedStorage(dbInstance.allocatedStorage());
        } catch (Exception exception) {
            return Commons.handleException(progress, exception, MODIFY_DB_INSTANCE_ERROR_RULE_SET);
        }
        return progress;
    }

    private ProgressEvent<ResourceModel, CallbackContext> setParameterGroupName(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final String dbParameterGroupName = progress.getResourceModel().getDBParameterGroupName();

        if (StringUtils.isNullOrEmpty(dbParameterGroupName)) {
            return progress;
        }

        final String engine = progress.getResourceModel().getEngine();
        final String engineVersion = progress.getResourceModel().getEngineVersion();

        DescribeDbParameterGroupsResponse response = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbParameterGroupsRequest(dbParameterGroupName),
                rdsProxyClient.client()::describeDBParameterGroups
        );

        final Optional<DBParameterGroup> maybeDbParameterGroup = response.dbParameterGroups().stream().findFirst();

        if (!maybeDbParameterGroup.isPresent()) {
            return progress;
        }

        final String dbParameterGroupFamily = maybeDbParameterGroup.get().dbParameterGroupFamily();
        final DescribeDbEngineVersionsResponse describeDbEngineVersionsResponse = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbEngineVersionsRequest(dbParameterGroupFamily, engine, engineVersion),
                rdsProxyClient.client()::describeDBEngineVersions
        );

        if (CollectionUtils.isNullOrEmpty(describeDbEngineVersionsResponse.dbEngineVersions())) {
            progress.getResourceModel().setDBParameterGroupName(null);
        } else {
            progress.getResourceModel().setDBParameterGroupName(dbParameterGroupName);
        }

        return progress;
    }

    private boolean shouldSetDefaultVpcId(final ResourceHandlerRequest<ResourceModel> request) {
        return CollectionUtils.isNullOrEmpty(request.getDesiredResourceState().getVPCSecurityGroups());
    }

    private boolean shouldUnsetMaxAllocatedStorage(final ResourceHandlerRequest<ResourceModel> request) {
        return request.getPreviousResourceState() != null &&
                request.getPreviousResourceState().getMaxAllocatedStorage() != null &&
                request.getDesiredResourceState().getMaxAllocatedStorage() == null;
    }

    private ProgressEvent<ResourceModel, CallbackContext> setDefaultVpcId(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {

        SecurityGroup securityGroup;

        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, progress.getResourceModel());
            final String vpcId = dbInstance.dbSubnetGroup().vpcId();
            securityGroup = fetchSecurityGroup(ec2ProxyClient, vpcId, "default");
        } catch (Exception e) {
            return Commons.handleException(progress, e, DEFAULT_DB_INSTANCE_ERROR_RULE_SET);
        }

        if (securityGroup != null) {
            final String groupId = securityGroup.groupId();
            if (StringUtils.hasValue(groupId)) {
                progress.getResourceModel().setDBSecurityGroups(Collections.singletonList(groupId));
            }
        }

        return progress;
    }

    private ProgressEvent<ResourceModel, CallbackContext> updateTags(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Collection<Tag> previousTags,
            final Collection<Tag> desiredTags
    ) {
        final Set<Tag> tagsToAdd = new HashSet<>(desiredTags);
        final Set<Tag> tagsToRemove = new HashSet<>(previousTags);

        tagsToAdd.removeAll(previousTags);
        tagsToRemove.removeAll(desiredTags);

        if (tagsToAdd.isEmpty() && tagsToRemove.isEmpty()) {
            return progress;
        }

        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, progress.getResourceModel());
            final String arn = dbInstance.dbInstanceArn();

            removeOldTags(rdsProxyClient, arn, tagsToRemove);
            addNewTags(rdsProxyClient, arn, tagsToAdd);
        } catch (Exception e) {
            return Commons.handleException(progress, e, DEFAULT_DB_INSTANCE_ERROR_RULE_SET);
        }

        return progress;
    }
}
