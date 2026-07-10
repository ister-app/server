package app.ister.core.config;

import app.ister.core.status.ProcessingActivityAdvice;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.rabbit.config.BaseRabbitListenerContainerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Appends the ProcessingActivityAdvice to every listener container factory's advice
 * chain. Appending (never replacing) keeps Boot's retry interceptor — built from the
 * spring.rabbitmq.listener.simple.retry.* properties — in front, so retry and
 * dead-lettering behave exactly as before and each retry attempt is tracked as
 * in-flight work. Done as a BeanPostProcessor on the factory because the container's
 * own getAdviceChain() is protected.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitInstrumentationConfig {

    private RabbitInstrumentationConfig() {
        // only a static @Bean factory method; never instantiated for behavior
    }

    @Bean
    public static BeanPostProcessor processingActivityAdviceApplier(ObjectProvider<ProcessingActivityAdvice> advice) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof BaseRabbitListenerContainerFactory<?> factory) {
                    Advice[] existing = factory.getAdviceChain();
                    Advice[] chain = existing == null
                            ? new Advice[]{advice.getObject()}
                            : Arrays.copyOf(existing, existing.length + 1);
                    chain[chain.length - 1] = advice.getObject();
                    factory.setAdviceChain(chain);
                }
                return bean;
            }
        };
    }
}
