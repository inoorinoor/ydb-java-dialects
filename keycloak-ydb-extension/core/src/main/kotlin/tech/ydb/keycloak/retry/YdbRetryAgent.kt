package tech.ydb.keycloak.retry

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.matcher.ElementMatchers
import java.lang.instrument.Instrumentation
import java.util.logging.Logger

object YdbRetryAgent {

    private val LOG = Logger.getLogger(YdbRetryAgent::class.java.name)

    @JvmStatic
    fun premain(args: String?, inst: Instrumentation) {
        LOG.info("YDB Retry Agent: installing transaction retry interceptor")

        AgentBuilder.Default()
            .type(ElementMatchers.named<TypeDescription>("org.keycloak.models.utils.KeycloakModelUtils"))
            .transform { builder, _, _, _, _ ->
                builder.method(
                    ElementMatchers.named<MethodDescription>("runJobInTransactionWithResult")
                        .and(ElementMatchers.takesArguments<MethodDescription>(4))
                        .and(ElementMatchers.isStatic<MethodDescription>())
                ).intercept(MethodDelegation.to(YdbRetryInterceptor::class.java))
            }
            .installOn(inst)

        LOG.info("YDB Retry Agent: interceptor installed successfully")
    }
}