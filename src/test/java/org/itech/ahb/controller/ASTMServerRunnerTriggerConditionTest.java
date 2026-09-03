package org.itech.ahb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ASTMServerRunnerTriggerConditionTest {

    @Test
    void disabledAstRuntimeDoesNotStartListeners() {
        ASTMServerRunner serverRunner = mock(ASTMServerRunner.class);
        ASTMServlet servlet = mock(ASTMServlet.class);

        contextRunner(serverRunner, servlet)
                .withPropertyValues("org.itech.ahb.astm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ASTMServerRunnerTrigger.class));
    }

    @Test
    void astRuntimeStartsListenersByDefault() {
        ASTMServerRunner serverRunner = mock(ASTMServerRunner.class);
        ASTMServlet servlet = mock(ASTMServlet.class);

        contextRunner(serverRunner, servlet).run(context -> {
            assertThat(context).hasSingleBean(ASTMServerRunnerTrigger.class);
            verify(serverRunner).run(servlet);
        });
    }

    private ApplicationContextRunner contextRunner(ASTMServerRunner serverRunner, ASTMServlet servlet) {
        return new ApplicationContextRunner()
                .withBean(ASTMServerRunner.class, () -> serverRunner)
                .withBean(ASTMServlet.class, () -> servlet)
                .withUserConfiguration(ASTMServerRunnerTrigger.class);
    }
}
