/*
 * Copyright 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.ops

import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.*
import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.config.GoogleConfigurationProperties
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.GoogleOperationPoller
import com.netflix.spinnaker.clouddriver.google.deploy.SafeRetry
import com.netflix.spinnaker.clouddriver.google.deploy.description.DestroyGoogleServerGroupDescription
import com.netflix.spinnaker.clouddriver.google.model.GoogleServerGroup
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.GoogleBackendService
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.GoogleHttpLoadBalancer
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.GoogleInternalLoadBalancer
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleClusterProvider
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleLoadBalancerProvider
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class DestroyGoogleServerGroupAtomicOperationUnitSpec extends Specification {
  private static final ACCOUNT_NAME = "auto"
  private static final PROJECT_NAME = "my_project"
  private static final SERVER_GROUP_NAME = "spinnaker-test-v000"
  private static final APPLICATION_NAME = Names.parseName(SERVER_GROUP_NAME).app
  private static final INSTANCE_TEMPLATE_NAME = "$SERVER_GROUP_NAME-${System.currentTimeMillis()}"
  private static final INSTANCE_GROUP_OP_NAME = "spinnaker-test-v000-op"
  private static final AUTOSCALERS_OP_NAME = "spinnaker-test-v000-autoscaler-op"
  private static final REGION = "us-central1"
  private static final ZONE = "us-central1-b"
  private static final DONE = "DONE"

  @Shared
  def threadSleeperMock = Mock(GoogleOperationPoller.ThreadSleeper)

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))

    // Yes this can affect other tests; but only in a good way.
    SafeRetry.SAFE_RETRY_INTERVAL_MILLIS = 1
  }

  void "should delete managed instance group"() {
    setup:
      def googleClusterProviderMock = Mock(GoogleClusterProvider)
      def serverGroup =
        new GoogleServerGroup(region: REGION,
                              zone: ZONE,
                              launchConfig: [instanceTemplate: new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME)]).view
      def computeMock = Mock(Compute)
      def instanceGroupManagersMock = Mock(Compute.InstanceGroupManagers)
      def zoneOperations = Mock(Compute.ZoneOperations)
      def zoneOperationsGet = Mock(Compute.ZoneOperations.Get)
      def instanceGroupManagersDeleteMock = Mock(Compute.InstanceGroupManagers.Delete)
      def instanceGroupManagersDeleteOp = new Operation(name: INSTANCE_GROUP_OP_NAME, status: DONE)
      def instanceTemplatesMock = Mock(Compute.InstanceTemplates)
      def instanceTemplatesDeleteMock = Mock(Compute.InstanceTemplates.Delete)
      def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
      googleLoadBalancerProviderMock.getApplicationLoadBalancers(APPLICATION_NAME) >> []
      def credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()
      def description = new DestroyGoogleServerGroupDescription(serverGroupName: SERVER_GROUP_NAME,
                                                                region: REGION,
                                                                accountName: ACCOUNT_NAME,
                                                                credentials: credentials)
      @Subject def operation = new DestroyGoogleServerGroupAtomicOperation(description)
      operation.googleOperationPoller =
        new GoogleOperationPoller(googleConfigurationProperties: new GoogleConfigurationProperties(),
                                  threadSleeper: threadSleeperMock)
      operation.googleClusterProvider = googleClusterProviderMock
      operation.googleLoadBalancerProvider = googleLoadBalancerProviderMock

    when:
      operation.operate([])

    then:
      1 * googleClusterProviderMock.getServerGroup(ACCOUNT_NAME, REGION, SERVER_GROUP_NAME) >> serverGroup

      1 * computeMock.instanceGroupManagers() >> instanceGroupManagersMock
      1 * instanceGroupManagersMock.delete(PROJECT_NAME, ZONE, SERVER_GROUP_NAME) >> instanceGroupManagersDeleteMock
      1 * instanceGroupManagersDeleteMock.execute() >> instanceGroupManagersDeleteOp

      1 * computeMock.zoneOperations() >> zoneOperations
      1 * zoneOperations.get(PROJECT_NAME, ZONE, INSTANCE_GROUP_OP_NAME) >> zoneOperationsGet
      1 * zoneOperationsGet.execute() >> instanceGroupManagersDeleteOp

      1 * computeMock.instanceTemplates() >> instanceTemplatesMock
      1 * instanceTemplatesMock.delete(PROJECT_NAME, INSTANCE_TEMPLATE_NAME) >> instanceTemplatesDeleteMock
      1 * instanceTemplatesDeleteMock.execute()
  }

  @Unroll
  void "should delete managed instance group and autoscaler if defined"() {
    setup:
      def googleClusterProviderMock = Mock(GoogleClusterProvider)
      def serverGroup =
        new GoogleServerGroup(region: REGION,
                              regional: isRegional,
                              zone: ZONE,
                              launchConfig: [instanceTemplate: new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME)],
                              autoscalingPolicy: [coolDownPeriodSec: 45,
                                                  minNumReplicas: 2,
                                                  maxNumReplicas: 5]).view
      def computeMock = Mock(Compute)
      def regionInstanceGroupManagersMock = Mock(Compute.RegionInstanceGroupManagers)
      def instanceGroupManagersMock = Mock(Compute.InstanceGroupManagers)
      def regionOperations = Mock(Compute.RegionOperations)
      def regionOperationsGet = Mock(Compute.RegionOperations.Get)
      def zoneOperations = Mock(Compute.ZoneOperations)
      def zoneOperationsGet = Mock(Compute.ZoneOperations.Get)
      def regionInstanceGroupManagersDeleteMock = Mock(Compute.RegionInstanceGroupManagers.Delete)
      def instanceGroupManagersDeleteMock = Mock(Compute.InstanceGroupManagers.Delete)
      def instanceGroupManagersDeleteOp = new Operation(name: INSTANCE_GROUP_OP_NAME, status: DONE)
      def instanceTemplatesMock = Mock(Compute.InstanceTemplates)
      def instanceTemplatesDeleteMock = Mock(Compute.InstanceTemplates.Delete)
      def regionAutoscalersMock = Mock(Compute.RegionAutoscalers)
      def regionAutoscalersDeleteMock = Mock(Compute.RegionAutoscalers.Delete)
      def autoscalersMock = Mock(Compute.Autoscalers)
      def autoscalersDeleteMock = Mock(Compute.Autoscalers.Delete)
      def autoscalersDeleteOp = new Operation(name: AUTOSCALERS_OP_NAME, status: DONE)
      def credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()
      def description = new DestroyGoogleServerGroupDescription(serverGroupName: SERVER_GROUP_NAME,
                                                                region: REGION,
                                                                accountName: ACCOUNT_NAME,
                                                                credentials: credentials)
      def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
      googleLoadBalancerProviderMock.getApplicationLoadBalancers(APPLICATION_NAME) >> []
      @Subject def operation = new DestroyGoogleServerGroupAtomicOperation(description)
      operation.googleOperationPoller =
        new GoogleOperationPoller(googleConfigurationProperties: new GoogleConfigurationProperties(),
                                  threadSleeper: threadSleeperMock)
      operation.googleClusterProvider = googleClusterProviderMock
      operation.googleLoadBalancerProvider = googleLoadBalancerProviderMock

    when:
      operation.operate([])

    then:
      1 * googleClusterProviderMock.getServerGroup(ACCOUNT_NAME, REGION, SERVER_GROUP_NAME) >> serverGroup

      if (isRegional) {
        1 * computeMock.regionAutoscalers() >> regionAutoscalersMock
        1 * regionAutoscalersMock.delete(PROJECT_NAME, location, SERVER_GROUP_NAME) >> regionAutoscalersDeleteMock
        1 * regionAutoscalersDeleteMock.execute() >> autoscalersDeleteOp

        1 * computeMock.regionOperations() >> regionOperations
        1 * regionOperations.get(PROJECT_NAME, location, AUTOSCALERS_OP_NAME) >> regionOperationsGet
        1 * regionOperationsGet.execute() >> autoscalersDeleteOp
      } else {
        1 * computeMock.autoscalers() >> autoscalersMock
        1 * autoscalersMock.delete(PROJECT_NAME, location, SERVER_GROUP_NAME) >> autoscalersDeleteMock
        1 * autoscalersDeleteMock.execute() >> autoscalersDeleteOp

        1 * computeMock.zoneOperations() >> zoneOperations
        1 * zoneOperations.get(PROJECT_NAME, location, AUTOSCALERS_OP_NAME) >> zoneOperationsGet
        1 * zoneOperationsGet.execute() >> autoscalersDeleteOp
      }

    then:
      if (isRegional) {
        1 * computeMock.regionInstanceGroupManagers() >> regionInstanceGroupManagersMock
        1 * regionInstanceGroupManagersMock.delete(PROJECT_NAME, location, SERVER_GROUP_NAME) >> regionInstanceGroupManagersDeleteMock
        1 * regionInstanceGroupManagersDeleteMock.execute() >> instanceGroupManagersDeleteOp

        1 * computeMock.regionOperations() >> regionOperations
        1 * regionOperations.get(PROJECT_NAME, location, INSTANCE_GROUP_OP_NAME) >> regionOperationsGet
        1 * regionOperationsGet.execute() >> instanceGroupManagersDeleteOp
      } else {
        1 * computeMock.instanceGroupManagers() >> instanceGroupManagersMock
        1 * instanceGroupManagersMock.delete(PROJECT_NAME, location, SERVER_GROUP_NAME) >> instanceGroupManagersDeleteMock
        1 * instanceGroupManagersDeleteMock.execute() >> instanceGroupManagersDeleteOp

        1 * computeMock.zoneOperations() >> zoneOperations
        1 * zoneOperations.get(PROJECT_NAME, location, INSTANCE_GROUP_OP_NAME) >> zoneOperationsGet
        1 * zoneOperationsGet.execute() >> instanceGroupManagersDeleteOp
      }

    then:
      1 * computeMock.instanceTemplates() >> instanceTemplatesMock
      1 * instanceTemplatesMock.delete(PROJECT_NAME, INSTANCE_TEMPLATE_NAME) >> instanceTemplatesDeleteMock
      1 * instanceTemplatesDeleteMock.execute()

    where:
      isRegional | location
      false      | ZONE
      true       | REGION
  }

  @Unroll
  void "should delete http loadbalancer backend if associated"() {
    setup:
      def googleClusterProviderMock = Mock(GoogleClusterProvider)
      def loadBalancerNameList = lbNames
      def serverGroup =
          new GoogleServerGroup(
              name: SERVER_GROUP_NAME,
              region: REGION,
              regional: isRegional,
              zone: ZONE,
              asg: [
                  (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES): loadBalancerNameList,
              ],
              launchConfig: [
                  instanceTemplate: new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME,
                      properties: [
                          'metadata': new Metadata(items: [
                              new Metadata.Items(
                                  key: (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES),
                                  value: 'spinnaker-http-load-balancer'
                              ),
                              new Metadata.Items(
                                  key: (GoogleServerGroup.View.BACKEND_SERVICE_NAMES),
                                  value: 'backend-service'
                              )
                          ])
                      ])
              ]).view
      def computeMock = Mock(Compute)
      def backendServicesMock = Mock(Compute.BackendServices)
      def backendSvcGetMock = Mock(Compute.BackendServices.Get)
      def backendUpdateMock = Mock(Compute.BackendServices.Update)
      def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
      googleLoadBalancerProviderMock.getApplicationLoadBalancers("") >> loadBalancerList
      def credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()
      def task = Mock(Task)
      def bs = isRegional ?
          new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildZonalServerGroupUrl(PROJECT_NAME, ZONE, serverGroup.name)) }) :
          new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildRegionalServerGroupUrl(PROJECT_NAME, REGION, serverGroup.name)) })

      def description = new DestroyGoogleServerGroupDescription(serverGroupName: SERVER_GROUP_NAME,
                                                                region: REGION,
                                                                accountName: ACCOUNT_NAME,
                                                                credentials: credentials)
      @Subject def operation = new DestroyGoogleServerGroupAtomicOperation(description)
      operation.googleOperationPoller =
          new GoogleOperationPoller(googleConfigurationProperties: new GoogleConfigurationProperties(),
                                    threadSleeper: threadSleeperMock)
      operation.googleClusterProvider = googleClusterProviderMock
      operation.googleLoadBalancerProvider = googleLoadBalancerProviderMock

    when:
      def closure = operation.destroyHttpLoadBalancerBackends(computeMock, PROJECT_NAME, serverGroup, googleLoadBalancerProviderMock)
      closure()

    then:
      _ * computeMock.backendServices() >> backendServicesMock
      _ * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      _ * backendSvcGetMock.execute() >> bs
      _ * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock
      _ * backendUpdateMock.execute()
      bs.backends.size == 0

    where:
      isRegional | location | loadBalancerList                                                         | lbNames
      false      | ZONE     |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer']
      true       | REGION   |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer']
      false      | ZONE     |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer']
      true       | REGION   |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer']
      false      | ZONE     |  []                                                                      | []
      true       | REGION   |  []                                                                      | []
  }

  @Unroll
  void "should delete internal loadbalancer backend if associated"() {
    setup:
    def googleClusterProviderMock = Mock(GoogleClusterProvider)
    def loadBalancerNameList = lbNames
    def serverGroup =
      new GoogleServerGroup(
        name: SERVER_GROUP_NAME,
        region: REGION,
        regional: isRegional,
        zone: ZONE,
        asg: [
          (GoogleServerGroup.View.REGIONAL_LOAD_BALANCER_NAMES): loadBalancerNameList,
        ],
        launchConfig: [
          instanceTemplate: new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME,
            properties: [
              'metadata': new Metadata(items: [
                new Metadata.Items(
                  key: (GoogleServerGroup.View.REGIONAL_LOAD_BALANCER_NAMES),
                  value: 'spinnaker-int-load-balancer'
                )
              ])
            ])
        ]).view
    def computeMock = Mock(Compute)
    def backendServicesMock = Mock(Compute.RegionBackendServices)
    def backendSvcGetMock = Mock(Compute.RegionBackendServices.Get)
    def backendUpdateMock = Mock(Compute.RegionBackendServices.Update)
    def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
    googleLoadBalancerProviderMock.getApplicationLoadBalancers("") >> loadBalancerList
    def credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()
    def bs = isRegional ?
      new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildZonalServerGroupUrl(PROJECT_NAME, ZONE, serverGroup.name)) }) :
      new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildRegionalServerGroupUrl(PROJECT_NAME, REGION, serverGroup.name)) })

    def description = new DestroyGoogleServerGroupDescription(serverGroupName: SERVER_GROUP_NAME,
      region: REGION,
      accountName: ACCOUNT_NAME,
      credentials: credentials)
    @Subject def operation = new DestroyGoogleServerGroupAtomicOperation(description)
    operation.googleOperationPoller =
      new GoogleOperationPoller(googleConfigurationProperties: new GoogleConfigurationProperties(),
        threadSleeper: threadSleeperMock)
    operation.googleClusterProvider = googleClusterProviderMock
    operation.googleLoadBalancerProvider = googleLoadBalancerProviderMock

    when:
    def closure = operation.destroyInternalLoadBalancerBackends(computeMock, PROJECT_NAME, serverGroup, googleLoadBalancerProviderMock)
    closure()

    then:
    _ * computeMock.regionBackendServices() >> backendServicesMock
    _ * backendServicesMock.get(PROJECT_NAME, REGION, 'backend-service') >> backendSvcGetMock
    _ * backendSvcGetMock.execute() >> bs
    _ * backendServicesMock.update(PROJECT_NAME, REGION, 'backend-service', bs) >> backendUpdateMock
    _ * backendUpdateMock.execute()
    bs.backends.size == 0

    where:
    isRegional | location | loadBalancerList                                                                                                                               | lbNames
    false      | ZONE     | [new GoogleInternalLoadBalancer(name: 'spinnaker-int-load-balancer', backendService: new GoogleBackendService(name: 'backend-service')).view] | ['spinnaker-int-load-balancer']
    true       | REGION   | [new GoogleInternalLoadBalancer(name: 'spinnaker-int-load-balancer', backendService: new GoogleBackendService(name: 'backend-service')).view] | ['spinnaker-int-load-balancer']
    false      | ZONE     | [new GoogleInternalLoadBalancer(name: 'spinnaker-int-load-balancer', backendService: new GoogleBackendService(name: 'backend-service')).view] | ['spinnaker-int-load-balancer']
    true       | REGION   | [new GoogleInternalLoadBalancer(name: 'spinnaker-int-load-balancer', backendService: new GoogleBackendService(name: 'backend-service')).view] | ['spinnaker-int-load-balancer']
    false      | ZONE     | []                                                                                                                                             | []
    true       | REGION   | []                                                                                                                                             | []
  }

  @Unroll
  void "should retry http backend deletion on 400, 412, socket timeout, succeed on 404"() {
    // Note: Implicitly tests SafeRetry.doRetry
    setup:
      def computeMock = Mock(Compute)
      def backendServicesMock = Mock(Compute.BackendServices)
      def backendSvcGetMock = Mock(Compute.BackendServices.Get)
      def backendUpdateMock = Mock(Compute.BackendServices.Update)
      def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
      googleLoadBalancerProviderMock.getApplicationLoadBalancers("") >> loadBalancerList

      def serverGroup =
          new GoogleServerGroup(
              name: SERVER_GROUP_NAME,
              region: REGION,
              regional: isRegional,
              zone: ZONE,
              asg: [
                  (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES): lbNames,
              ],
              launchConfig: [
                  instanceTemplate: new InstanceTemplate(name: INSTANCE_TEMPLATE_NAME,
                      properties: [
                          'metadata': new Metadata(items: [
                              new Metadata.Items(
                                  key: (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES),
                                  value: 'spinnaker-http-load-balancer'
                              ),
                              new Metadata.Items(
                                  key: (GoogleServerGroup.View.BACKEND_SERVICE_NAMES),
                                  value: 'backend-service'
                              )
                          ])
                      ])
              ]).view

      def errorMessage = "The resource 'my-backend-service' is not ready"
      def errorInfo = new GoogleJsonError.ErrorInfo(
          domain: "global",
          message: errorMessage,
          reason: "resourceNotReady")
      def details = new GoogleJsonError(
          code: 400,
          errors: [errorInfo],
          message: errorMessage)
      def httpResponseExceptionBuilder = new HttpResponseException.Builder(
          400,
          "Bad Request",
          new HttpHeaders()).setMessage("400 Bad Request")
      def googleJsonResponseException = new GoogleJsonResponseException(httpResponseExceptionBuilder, details)

      errorMessage = "Invalid fingerprint."
      errorInfo = new GoogleJsonError.ErrorInfo(
          domain: "global",
          message: errorMessage,
          reason: "conditionNotMet")
      details = new GoogleJsonError(
          code: 412,
          errors: [errorInfo],
          message: errorMessage)
      httpResponseExceptionBuilder = new HttpResponseException.Builder(
          412,
          "Precondition Failed",
          new HttpHeaders()).setMessage("412 Precondition Failed")
      def fingerPrintException = new GoogleJsonResponseException(httpResponseExceptionBuilder, details)

      errorMessage = "Resource 'stuff' could not be located"
      errorInfo = new GoogleJsonError.ErrorInfo(
          domain: "global",
          message: errorMessage,
          reason: "stuffNotFound")
      details = new GoogleJsonError(
          code: 404,
          errors: [errorInfo],
          message: errorMessage)
      httpResponseExceptionBuilder = new HttpResponseException.Builder(
          404,
          "Not Found",
          new HttpHeaders()).setMessage("404 Not Found")
      def notFoundException = new GoogleJsonResponseException(httpResponseExceptionBuilder, details)

      def socketTimeoutException = new SocketTimeoutException("Read timed out")

      def bs = isRegional ?
          new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildZonalServerGroupUrl(PROJECT_NAME, ZONE, serverGroup.name)) }) :
          new BackendService(backends: lbNames.collect { new Backend(group: GCEUtil.buildRegionalServerGroupUrl(PROJECT_NAME, REGION, serverGroup.name)) })

    when:
      DestroyGoogleServerGroupAtomicOperation.destroy(
          DestroyGoogleServerGroupAtomicOperation.destroyHttpLoadBalancerBackends(computeMock, PROJECT_NAME, serverGroup, googleLoadBalancerProviderMock),
          "Http load balancer backends"
      )

    then:
      1 * backendUpdateMock.execute() >> { throw googleJsonResponseException }
      2 * computeMock.backendServices() >> backendServicesMock
      1 * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      1 * backendSvcGetMock.execute() >> bs
      1 * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock

    then:
      1 * backendUpdateMock.execute() >> { throw fingerPrintException }
      2 * computeMock.backendServices() >> backendServicesMock
      1 * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      1 * backendSvcGetMock.execute() >> bs
      1 * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock

    then:
      1 * backendUpdateMock.execute() >> { throw socketTimeoutException }
      2 * computeMock.backendServices() >> backendServicesMock
      1 * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      1 * backendSvcGetMock.execute() >> bs
      1 * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock

    then:
      1 * backendUpdateMock.execute()
      2 * computeMock.backendServices() >> backendServicesMock
      1 * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      1 * backendSvcGetMock.execute() >> bs
      1 * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock

    when:
      DestroyGoogleServerGroupAtomicOperation.destroy(
          DestroyGoogleServerGroupAtomicOperation.destroyHttpLoadBalancerBackends(computeMock, PROJECT_NAME, serverGroup, googleLoadBalancerProviderMock),
          "Http load balancer backends"
      )

    then:
      // Note: retry fails once and we retry once to verify
      2 * backendUpdateMock.execute() >> { throw notFoundException }
      4 * computeMock.backendServices() >> backendServicesMock
      2 * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      2 * backendSvcGetMock.execute() >> bs
      2 * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock

    where:
      isRegional | location | loadBalancerList                                                         | lbNames
      false      | ZONE     |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer']
  }
}
