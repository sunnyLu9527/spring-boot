/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.amqp;

import java.time.Duration;
import java.util.Objects;

import com.rabbitmq.client.Channel;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitMessagingTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ReflectionUtils;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for {@link RabbitTemplate}.
 * <p>
 * This configuration class is active only when the RabbitMQ and Spring AMQP client
 * libraries are on the classpath.
 * <P>
 * Registers the following beans:
 * <ul>
 * <li>{@link org.springframework.amqp.rabbit.core.RabbitTemplate RabbitTemplate} if there
 * is no other bean of the same type in the context.</li>
 * <li>{@link org.springframework.amqp.rabbit.connection.CachingConnectionFactory
 * CachingConnectionFactory} instance if there is no other bean of the same type in the
 * context.</li>
 * <li>{@link org.springframework.amqp.core.AmqpAdmin } instance as long as
 * {@literal spring.rabbitmq.dynamic=true}.</li>
 * </ul>
 * <p>
 * The {@link org.springframework.amqp.rabbit.connection.CachingConnectionFactory} honors
 * the following properties:
 * <ul>
 * <li>{@literal spring.rabbitmq.port} is used to specify the port to which the client
 * should connect, and defaults to 5672.</li>
 * <li>{@literal spring.rabbitmq.username} is used to specify the (optional) username.
 * </li>
 * <li>{@literal spring.rabbitmq.password} is used to specify the (optional) password.
 * </li>
 * <li>{@literal spring.rabbitmq.host} is used to specify the host, and defaults to
 * {@literal localhost}.</li>
 * <li>{@literal spring.rabbitmq.virtualHost} is used to specify the (optional) virtual
 * host to which the client should connect.</li>
 * </ul>
 *
 * @author Greg Turnquist
 * @author Josh Long
 * @author Stephane Nicoll
 * @author Gary Russell
 * @author Phillip Webb
 * @since 1.0.0
 */
@Configuration
@ConditionalOnClass({ RabbitTemplate.class, Channel.class })
@EnableConfigurationProperties(RabbitProperties.class)
@Import(RabbitAnnotationDrivenConfiguration.class)
public class RabbitAutoConfiguration {

	@Configuration
	@ConditionalOnMissingBean(ConnectionFactory.class)
	protected static class RabbitConnectionFactoryCreator {

		// Only available in rabbitmq-java-client 5.4.0 +
		private static final boolean CAN_ENABLE_HOSTNAME_VERIFICATION = ReflectionUtils
				.findMethod(com.rabbitmq.client.ConnectionFactory.class, "enableHostnameVerification") != null;

		@Bean
		public CachingConnectionFactory rabbitConnectionFactory(RabbitProperties properties,
				ObjectProvider<ConnectionNameStrategy> connectionNameStrategy) throws Exception {
			PropertyMapper map = PropertyMapper.get();
			CachingConnectionFactory factory = new CachingConnectionFactory(
					getRabbitConnectionFactoryBean(properties).getObject());
			map.from(properties::determineAddresses).to(factory::setAddresses);
			map.from(properties::isPublisherConfirms).to(factory::setPublisherConfirms);
			map.from(properties::isPublisherReturns).to(factory::setPublisherReturns);
			RabbitProperties.Cache.Channel channel = properties.getCache().getChannel();
			map.from(channel::getSize).whenNonNull().to(factory::setChannelCacheSize);
			map.from(channel::getCheckoutTimeout).whenNonNull().as(Duration::toMillis)
					.to(factory::setChannelCheckoutTimeout);
			RabbitProperties.Cache.Connection connection = properties.getCache().getConnection();
			map.from(connection::getMode).whenNonNull().to(factory::setCacheMode);
			map.from(connection::getSize).whenNonNull().to(factory::setConnectionCacheSize);
			map.from(connectionNameStrategy::getIfUnique).whenNonNull().to(factory::setConnectionNameStrategy);
			return factory;
		}

		private RabbitConnectionFactoryBean getRabbitConnectionFactoryBean(RabbitProperties properties)
				throws Exception {
			PropertyMapper map = PropertyMapper.get();
			RabbitConnectionFactoryBean factory = new RabbitConnectionFactoryBean();
			map.from(properties::determineHost).whenNonNull().to(factory::setHost);
			map.from(properties::determinePort).to(factory::setPort);
			map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
			map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
			map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
			map.from(properties::getRequestedHeartbeat).whenNonNull().asInt(Duration::getSeconds)
					.to(factory::setRequestedHeartbeat);
			RabbitProperties.Ssl ssl = properties.getSsl();
			if (ssl.isEnabled()) {
				factory.setUseSSL(true);
				map.from(ssl::getAlgorithm).whenNonNull().to(factory::setSslAlgorithm);
				map.from(ssl::getKeyStoreType).to(factory::setKeyStoreType);
				map.from(ssl::getKeyStore).to(factory::setKeyStore);
				map.from(ssl::getKeyStorePassword).to(factory::setKeyStorePassphrase);
				map.from(ssl::getTrustStoreType).to(factory::setTrustStoreType);
				map.from(ssl::getTrustStore).to(factory::setTrustStore);
				map.from(ssl::getTrustStorePassword).to(factory::setTrustStorePassphrase);
				map.from(ssl::isValidateServerCertificate)
						.to((validate) -> factory.setSkipServerCertificateValidation(!validate));
				map.from(ssl::getVerifyHostname).when(Objects::nonNull).to(factory::setEnableHostnameVerification);
				if (ssl.getVerifyHostname() == null && CAN_ENABLE_HOSTNAME_VERIFICATION) {
					factory.setEnableHostnameVerification(true);
				}
			}
			map.from(properties::getConnectionTimeout).whenNonNull().asInt(Duration::toMillis)
					.to(factory::setConnectionTimeout);
			factory.afterPropertiesSet();
			return factory;
		}

	}

	@Configuration
	@Import(RabbitConnectionFactoryCreator.class)
	protected static class RabbitTemplateConfiguration {

		private final ObjectProvider<MessageConverter> messageConverter;

		private final RabbitProperties properties;

		public RabbitTemplateConfiguration(ObjectProvider<MessageConverter> messageConverter,
				RabbitProperties properties) {
			this.messageConverter = messageConverter;
			this.properties = properties;
		}

		@Bean
		@ConditionalOnSingleCandidate(ConnectionFactory.class)
		@ConditionalOnMissingBean
		public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
			PropertyMapper map = PropertyMapper.get();
			RabbitTemplate template = new RabbitTemplate(connectionFactory);
			MessageConverter messageConverter = this.messageConverter.getIfUnique();
			if (messageConverter != null) {
				template.setMessageConverter(messageConverter);
			}
			template.setMandatory(determineMandatoryFlag());
			RabbitProperties.Template properties = this.properties.getTemplate();
			if (properties.getRetry().isEnabled()) {
				template.setRetryTemplate(createRetryTemplate(properties.getRetry()));
			}
			map.from(properties::getReceiveTimeout).whenNonNull().as(Duration::toMillis)
					.to(template::setReceiveTimeout);
			map.from(properties::getReplyTimeout).whenNonNull().as(Duration::toMillis).to(template::setReplyTimeout);
			map.from(properties::getExchange).to(template::setExchange);
			map.from(properties::getRoutingKey).to(template::setRoutingKey);
			return template;
		}

		private boolean determineMandatoryFlag() {
			Boolean mandatory = this.properties.getTemplate().getMandatory();
			return (mandatory != null) ? mandatory : this.properties.isPublisherReturns();
		}

		private RetryTemplate createRetryTemplate(RabbitProperties.Retry properties) {
			PropertyMapper map = PropertyMapper.get();
			RetryTemplate template = new RetryTemplate();
			SimpleRetryPolicy policy = new SimpleRetryPolicy();
			map.from(properties::getMaxAttempts).to(policy::setMaxAttempts);
			template.setRetryPolicy(policy);
			ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
			map.from(properties::getInitialInterval).whenNonNull().as(Duration::toMillis)
					.to(backOffPolicy::setInitialInterval);
			map.from(properties::getMultiplier).to(backOffPolicy::setMultiplier);
			map.from(properties::getMaxInterval).whenNonNull().as(Duration::toMillis).to(backOffPolicy::setMaxInterval);
			template.setBackOffPolicy(backOffPolicy);
			return template;
		}

		@Bean
		@ConditionalOnSingleCandidate(ConnectionFactory.class)
		@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "dynamic", matchIfMissing = true)
		@ConditionalOnMissingBean
		public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
			return new RabbitAdmin(connectionFactory);
		}

	}

	@Configuration
	@ConditionalOnClass(RabbitMessagingTemplate.class)
	@ConditionalOnMissingBean(RabbitMessagingTemplate.class)
	@Import(RabbitTemplateConfiguration.class)
	protected static class MessagingTemplateConfiguration {

		@Bean
		@ConditionalOnSingleCandidate(RabbitTemplate.class)
		public RabbitMessagingTemplate rabbitMessagingTemplate(RabbitTemplate rabbitTemplate) {
			return new RabbitMessagingTemplate(rabbitTemplate);
		}

	}

}
