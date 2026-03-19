package com.example.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class ExampleTestBase {

    @Container
    protected static GenericContainer<?> mockToss = new GenericContainer<>(
            "ghtjr410/mock-toss:latest")
            .withExposedPorts(8090)
            .waitingFor(Wait.forHttp("/chaos/mode").forStatusCode(200));

    @Autowired
    protected PaymentClient paymentClient;

    @BeforeEach
    void printTestHeader(TestInfo testInfo) {
        String name = testInfo.getDisplayName().replace("_", " ");
        System.out.printf("%n═══ %s ═══%n", name);
    }

    @BeforeEach
    void resetMockServer() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000);
        paymentClient.resetTest();
    }
}
