/**
 * SPDX-FileCopyrightText: 2018-2023 SAP SE or an SAP affiliate company and Cloud Security Client Java contributors
 * <p>
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.cloud.security.test.integration.ssrf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;

import java.io.IOException;

import com.sap.cloud.security.config.OAuth2ServiceConfigurationBuilder;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.test.extension.SecurityTestExtension;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenHeader;
import com.sap.cloud.security.token.validation.CombiningValidator;
import com.sap.cloud.security.token.validation.ValidationResult;
import com.sap.cloud.security.token.validation.validators.JwtValidatorBuilder;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Test cases for <a href=
 * "https://owasp.org/www-community/attacks/Server_Side_Request_Forgery">SSRF
 * (Server Side Request Forgery)</a> attacks.
 *
 */
class JavaSSRFAttackTest {

	private final CloseableHttpClient httpClient = Mockito.spy(HttpClients.createDefault());

	@RegisterExtension
	static SecurityTestExtension extension = SecurityTestExtension.forService(Service.XSUAA).setPort(4242);

	/**
	 * This tests checks that an attacker cannot trick the token validation into
	 * using his/her own token key server by manipulating the JWKS url. The
	 * challenge is to redirect the request to a different token key server without
	 * the token validation steps to fail.
	 *
	 * @param jwksUrl
	 *            the JWKS url containing malicious parts
	 * @param isValid
	 *            {@code true} if the token validation is expected to be successful
	 */
	@ParameterizedTest
	@CsvSource({
			"http://localhost:4242/token_keys,											true",
			"http://localhost:4242/token_keys@malicious.ondemand.com/token_keys,		false",
			"http://user@localhost:4242/token_keys,										true", // user info in URI is deprecated by Apache HttpClient 5, but working in HttpClient 4.x.x
			"http://localhost:4242/token_keys///malicious.ondemand.com/token_keys,		false",
	})
	void maliciousPartOfJwksIsNotUsedToObtainToken(String jwksUrl, boolean isValid) throws IOException {
		OAuth2ServiceConfigurationBuilder configuration = extension.getContext()
				.getOAuth2ServiceConfigurationBuilderFromFile("/xsuaa/vcap_services-single.json");
		Token token = extension.getContext().getJwtGeneratorFromFile("/xsuaa/token.json")
				.withHeaderParameter(TokenHeader.JWKS_URL, jwksUrl)
				.createToken();
		CombiningValidator<Token> tokenValidator = JwtValidatorBuilder
				.getInstance(configuration.build())
				.withHttpClient(httpClient)
				.build();

		ValidationResult result = tokenValidator.validate(token);

		assertThat(result.isValid()).isEqualTo(isValid);
		ArgumentCaptor<HttpUriRequest> httpUriRequestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
		ArgumentCaptor<ResponseHandler> responseHandlerCaptor = ArgumentCaptor.forClass(ResponseHandler.class);

		Mockito.verify(httpClient, times(1)).execute(httpUriRequestCaptor.capture(), responseHandlerCaptor.capture());
		HttpUriRequest request = httpUriRequestCaptor.getValue();
		assertThat(request.getURI().getHost()).isEqualTo("localhost"); // ensure request was sent to trusted host
	}

}
