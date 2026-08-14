package org.itech.ahb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("ASTM listener lifecycle")
class ASTMServerRunnerTriggerTest {

    @Test
    @DisplayName("Listener trigger is absent when ASTM listeners are disabled")
    void disabledListenersDoNotCreateRunnerTrigger() {
        ASTMServerRunner runner = mock(ASTMServerRunner.class);
        ASTMServlet servlet = mock(ASTMServlet.class);

        contextRunner(runner, servlet)
                .withPropertyValues("org.itech.ahb.astm.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ASTMServerRunnerTrigger.class));
    }

    @Test
    @DisplayName("ASTM listeners remain enabled by default for production compatibility")
    void listenersRemainEnabledByDefault() {
        ASTMServerRunner runner = mock(ASTMServerRunner.class);
        ASTMServlet servlet = mock(ASTMServlet.class);

        contextRunner(runner, servlet).run(context -> {
            assertThat(context).hasSingleBean(ASTMServerRunnerTrigger.class);
            verify(runner).run(servlet);
        });
        verify(servlet).stop();
    }

    private ApplicationContextRunner contextRunner(ASTMServerRunner runner, ASTMServlet servlet) {
        return new ApplicationContextRunner()
                .withBean(ASTMServerRunner.class, () -> runner)
                .withBean(ASTMServlet.class, () -> servlet)
                .withUserConfiguration(ASTMServerRunnerTrigger.class);
    }
}
