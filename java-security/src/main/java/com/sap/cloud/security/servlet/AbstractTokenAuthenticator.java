/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.servlet;

import com.sap.cloud.security.config.CacheConfiguration;
import com.sap.cloud.security.config.OAuth2ServiceConfiguration;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.config.ServiceConstants;
import com.sap.cloud.security.token.SecurityContext;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.validation.ValidationListener;
import com.sap.cloud.security.token.validation.ValidationResult;
import com.sap.cloud.security.token.validation.Validator;
import com.sap.cloud.security.token.validation.validators.JwtValidatorBuilder;
import com.sap.cloud.security.xsuaa.http.HttpHeaders;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.sap.cloud.security.x509.X509Constants.FWD_CLIENT_CERT_HEADER;

public abstract class AbstractTokenAuthenticator implements TokenAuthenticator {

	private static final Logger logger = LoggerFactory.getLogger(AbstractTokenAuthenticator.class);
	private final List<ValidationListener> validationListeners = new ArrayList<>();
	private Validator<Token> tokenValidator;
	protected CloseableHttpClient httpClient;
	protected OAuth2ServiceConfiguration serviceConfiguration;
	private CacheConfiguration tokenKeyCacheConfiguration;

	@Override
	public TokenAuthenticationResult validateRequest(ServletRequest request, ServletResponse response) {
		if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse) {
			String authorizationHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
			if (headerIsAvailable(authorizationHeader)) {
				try {
					Token token = Token.create(authorizationHeader);
					return tokenValidationResult(token);
				} catch (Exception e) {
					return unauthenticated("Unexpected error occurred: " + e.getMessage());
				}
			} else {
				return unauthenticated("Authorization header is missing.");
			}
		}
		return TokenAuthenticatorResult.createUnauthenticated("Could not process request " + request);
	}

	/**
	 * Use to configure the token key cache.
	 *
	 * @param cacheConfiguration
	 *            the cache configuration
	 * @return this authenticator
	 */
	public AbstractTokenAuthenticator withCacheConfiguration(CacheConfiguration cacheConfiguration) {
		this.tokenKeyCacheConfiguration = cacheConfiguration;
		return this;
	}

	/**
	 * Use to configure the HttpClient that is used to retrieve token keys or to
	 * perform a token-exchange.
	 *
	 * @param httpClient
	 *            the HttpClient
	 * @return this authenticator
	 */
	public AbstractTokenAuthenticator withHttpClient(CloseableHttpClient httpClient) {
		this.httpClient = httpClient;
		return this;
	}

	/**
	 * Use to override the service configuration used.
	 *
	 * @param serviceConfiguration
	 *            the service configuration to use
	 * @return this authenticator
	 */
	public AbstractTokenAuthenticator withServiceConfiguration(OAuth2ServiceConfiguration serviceConfiguration) {
		this.serviceConfiguration = serviceConfiguration;
		setupTokenFactory();
		return this;
	}

	private void setupTokenFactory() {
		if (serviceConfiguration.getService() == Service.XSUAA) {
			HybridTokenFactory.withXsuaaAppId(serviceConfiguration.getProperty(ServiceConstants.XSUAA.APP_ID));
		}
	}

	/**
	 * Adds the validation listener to the jwt validator that is being used by the
	 * authenticator to validate the tokens.
	 *
	 * @param validationListener
	 *            the listener to be added.
	 * @return the authenticator instance
	 */
	public AbstractTokenAuthenticator withValidationListener(ValidationListener validationListener) {
		this.validationListeners.add(validationListener);
		return this;
	}

	/**
	 * Return configured service configuration or Environments.getCurrent() if not
	 * configured.
	 *
	 * @return the actual service configuration
	 * @throws IllegalStateException
	 *             in case service configuration is null
	 */
	protected abstract OAuth2ServiceConfiguration getServiceConfiguration();

	/**
	 * Return other configured service configurations or null if not configured.
	 *
	 * @return the other service configuration or null
	 */
	@Nullable
	protected abstract OAuth2ServiceConfiguration getOtherServiceConfiguration();

	/**
	 * Extracts the {@link Token} from the authorization header.
	 *
	 * @param authorizationHeader
	 *            the value of the 'Authorization' request header
	 * @return the {@link Token} instance.
	 */
	protected abstract Token extractFromHeader(String authorizationHeader);

	Validator<Token> getOrCreateTokenValidator() {
		if (tokenValidator == null) {
			JwtValidatorBuilder jwtValidatorBuilder = JwtValidatorBuilder.getInstance(getServiceConfiguration())
					.withHttpClient(httpClient);
			jwtValidatorBuilder.configureAnotherServiceInstance(getOtherServiceConfiguration());
			Optional.ofNullable(tokenKeyCacheConfiguration).ifPresent(jwtValidatorBuilder::withCacheConfiguration);
			validationListeners.forEach(jwtValidatorBuilder::withValidatorListener);
			tokenValidator = jwtValidatorBuilder.build();
		}
		return tokenValidator;
	}

	TokenAuthenticationResult unauthenticated(String message) {
		logger.warn("Request could not be authenticated: {}.", message);
		return TokenAuthenticatorResult.createUnauthenticated(message);
	}

	protected TokenAuthenticationResult authenticated(Token token) {
		return TokenAuthenticatorResult.createAuthenticated(Collections.emptyList(), token);
	}

	boolean headerIsAvailable(String authorizationHeader) {
		return authorizationHeader != null && !authorizationHeader.isEmpty();
	}

	TokenAuthenticationResult tokenValidationResult(Token token) {
		Validator<Token> validator = getOrCreateTokenValidator();
		ValidationResult result = validator.validate(token);
		if (result.isValid()) {
			SecurityContext.setToken(token);
			return authenticated(token);
		} else {
			return unauthenticated("Error during token validation: " + result.getErrorDescription());
		}
	}

	/**
	 * Extracts the forwarded client certificate from 'x-forwarded-client-cert'
	 * header.
	 *
	 * @param request
	 *            the HttpServletRequest
	 * @return the client certificate object
	 */
	@Nullable
	String getClientCertificate(HttpServletRequest request) {
		String clientCert = request.getHeader(FWD_CLIENT_CERT_HEADER);
		if (clientCert == null) {
			logger.info("There is no '{}' header provided", FWD_CLIENT_CERT_HEADER);
		}
		return clientCert;
	}

}
