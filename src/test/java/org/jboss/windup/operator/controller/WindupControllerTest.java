package org.jboss.windup.operator.controller;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.java.Log;
import org.awaitility.Awaitility;
import org.jboss.windup.operator.KubernetesCrudRecorderDispatcher;
import org.jboss.windup.operator.model.WindupResource;
import org.jboss.windup.operator.model.WindupResourceList;
import org.jboss.windup.operator.model.WindupResourceStatusCondition;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Log
@QuarkusTest
public class WindupControllerTest {
    @Inject
    KubernetesCrudRecorderDispatcher dispatcher;

    @Inject
    WindupController windupController;

    @Inject
    KubernetesMockServer server;

    @Inject
    MixedOperation<WindupResource, WindupResourceList, Resource<WindupResource>> crClient;

    @Inject
    KubernetesClient client;

    @Test
    public void onAddCR_shouldServerReceiveExactCalls() throws InterruptedException {
        InputStream fileStream = WindupControllerTest.class.getResourceAsStream("/windup.resource.yaml");
        WindupResource windupResource = Serialization.unmarshal(fileStream, WindupResource.class);
        crClient.inNamespace("test").create(windupResource);

        dispatcher.getRequests().clear();

        Awaitility
            .await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(2, dispatcher.getRequests().stream().filter(e-> "POST".equalsIgnoreCase(e.getMethod()) && e.getPath().contains("ingress")).count()));

        assertEquals(4, dispatcher.getRequests().stream().filter(e-> "PUT".equalsIgnoreCase(e.getMethod()) && e.getPath().contains("status") ).count());
        assertEquals(2, dispatcher.getRequests().stream().filter(e-> "POST".equalsIgnoreCase(e.getMethod()) && e.getPath().contains("persistentvolumeclaim")).count());
        assertEquals(3, dispatcher.getRequests().stream().filter(e-> "POST".equalsIgnoreCase(e.getMethod()) && e.getPath().contains("deployments") ).count());
        assertEquals(3, dispatcher.getRequests().stream().filter(e-> "POST".equalsIgnoreCase(e.getMethod()) && e.getPath().contains("service")).count());
    }

    // @format:off
    @Test
    public void onAccCR_orderOfReadyStatusShouldBeTheExpectedOne() throws InterruptedException {
        InputStream fileStream = WindupControllerTest.class.getResourceAsStream("/windup.resource.yaml");
        WindupResource windupResource = Serialization.unmarshal(fileStream, WindupResource.class);

        dispatcher.getRequests().clear();

        crClient.inNamespace("test").create(windupResource);

        Awaitility
        .await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted( () -> {
            assertEquals(3, client.apps().deployments().inNamespace("test").list().getItems().size());
        });

        setDeploymentReadyReplicas("windupapp-postgresql", 1);
        Thread.sleep(200);
        setDeploymentReadyReplicas("windupapp", 1);
        Thread.sleep(200);
        setDeploymentReadyReplicas("windupapp-executor", 1);

        Awaitility
            .await()
            .atMost(20, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                WindupResourceList lista = crClient.inNamespace("test").list();
                List<WindupResourceStatusCondition> status = lista.getItems().get(0).getStatus().getConditions();
                // Checking there are only 5 status in the CR : Deployment, Ready, windupapp, windupapp-executor, windupapp-postgresql
                assertEquals(5, status.size());
                // Checking there are 4 status with True : Ready, windupapp, windupapp-executor, windupapp-postgresql
                assertEquals(4, status.stream().filter(e -> "True".equalsIgnoreCase(e.getStatus())).count());
                // Checking there are 5 status with different timestamps : Deployment, Ready, windupapp, windupapp-executor, windupapp-postgresql
                assertEquals(5, status.stream().map(e -> e.getLastTransitionTime()).distinct().count());

                // Checking the update times are those expected in order
                LocalDateTime postgreUpdateTime = getLastTransitionTimeFromStatus(status, "windupapp-postgresql");
                log.info("postgre update time : " + postgreUpdateTime);
                LocalDateTime windupappUpdateTime = getLastTransitionTimeFromStatus(status, "windupapp");
                log.info("windupappUpdateTime : " + postgreUpdateTime);
                LocalDateTime executorUpdateTime = getLastTransitionTimeFromStatus(status, "windupapp-executor");
                log.info("windupapp-executor : " + postgreUpdateTime);

                assertTrue(windupappUpdateTime.isAfter(postgreUpdateTime.plus(Duration.ofMillis(150))));
                assertTrue(executorUpdateTime.isAfter(windupappUpdateTime.plus(Duration.ofMillis(150))));

            }) ;

    }

    private LocalDateTime getLastTransitionTimeFromStatus(List<WindupResourceStatusCondition> status, String deployment) {
        return status.stream()
                    .filter(e -> deployment.equalsIgnoreCase(e.getType())).findFirst()
                    .map(e -> LocalDateTime.parse(e.getLastTransitionTime(), DateTimeFormatter.ISO_DATE_TIME))
                    .get();
    }

    private void setDeploymentReadyReplicas(String name, int replicas) {
        Deployment deployment = client.apps().deployments().inNamespace("test").withName(name).get();
        if (deployment.getStatus() == null) deployment.setStatus(new DeploymentStatus());
        deployment.getStatus().setReadyReplicas(replicas);
        client.apps().deployments().updateStatus(deployment);
    }


}
