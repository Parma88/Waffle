/*
 * MIT License
 *
 * Copyright (c) 2010-2023 The Waffle Project Contributors: https://github.com/Waffle/waffle/graphs/contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package waffle.spring;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Supports optional injection of spring security entities, allowing Waffle to act as an interface towards an identity
 * provider(the AD).
 * <p>
 * <i>Below mentioned entities are verified to be set before invoked, inherited entities are not.</i>
 * <ul>
 * <li>The <code>AuthenticationManager</code> allows for the service provider to authorize the principal.</li>
 * <li>The <code>authenticationSuccessHandler</code> allows for the service provider to further populate the
 * {@link org.springframework.security.core.Authentication Authentication} object.</li>
 * <li>The <code>AuthenticationFailureHandler</code> is called if the AuthenticationManager throws an
 * {@link org.springframework.security.core.AuthenticationException AuthenticationException}.</li>
 * <li>The <code>AccessDeniedHandler</code> is called if the AuthenticationManager throws an
 * {@link org.springframework.security.access.AccessDeniedException AccessDeniedException}.</li>
 * </ul>
 * Example configuration:
 *
 * <pre>
 * {@code
 * <bean id="waffleNegotiateSecurityFilter"
 *      class="waffle.spring.DelegatingNegotiateSecurityFilter"
 *      scope="tenant">
 *      <property name="allowGuestLogin" value="false" />
 *      <property name="Provider" ref="waffleSecurityFilterProviderCollection" />
 *      <property name="authenticationManager" ref="authenticationManager" />
 *      <property name="authenticationSuccessHandler" ref="authenticationSuccessHandler" />
 *      <property name="authenticationFailureHandler" ref="authenticationFailureHandler" />
 *      <property name="accessDeniedHandler" ref="accessDeniedHandler" />
 *      <property name="defaultGrantedAuthority">
 *          <null />
 *      </property>
 * </bean>
 * }
 * </pre>
 */
public class DelegatingNegotiateSecurityFilter extends NegotiateSecurityFilter {

    /** The Constant LOGGER. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DelegatingNegotiateSecurityFilter.class);

    /** The authentication manager. */
    private AuthenticationManager authenticationManager;

    /** The authentication success handler. */
    private AuthenticationSuccessHandler authenticationSuccessHandler;

    /** The authentication failure handler. */
    private AuthenticationFailureHandler authenticationFailureHandler;

    /** The access denied handler. */
    private AccessDeniedHandler accessDeniedHandler;

    /**
     * Instantiates a new delegating negotiate security filter.
     */
    public DelegatingNegotiateSecurityFilter() {
        super();
        DelegatingNegotiateSecurityFilter.LOGGER.debug("[waffle.spring.NegotiateSecurityFilter] loaded");
    }

    /**
     * Gets the access denied handler.
     *
     * @return the accessDeniedHandler
     */
    public AccessDeniedHandler getAccessDeniedHandler() {
        return this.accessDeniedHandler;
    }

    /**
     * Sets the access denied handler.
     *
     * @param value
     *            the accessDeniedHandler to set
     */
    public void setAccessDeniedHandler(final AccessDeniedHandler value) {
        this.accessDeniedHandler = value;
    }

    /**
     * Gets the authentication failure handler.
     *
     * @return the authenticationFailureHandler
     */
    public AuthenticationFailureHandler getAuthenticationFailureHandler() {
        return this.authenticationFailureHandler;
    }

    /**
     * Sets the authentication failure handler.
     *
     * @param value
     *            the authenticationFailureHandler to set
     */
    public void setAuthenticationFailureHandler(final AuthenticationFailureHandler value) {
        this.authenticationFailureHandler = value;
    }

    @Override
    protected boolean setAuthentication(final HttpServletRequest request, final HttpServletResponse response,
            final Authentication authentication) {
        try {
            Authentication delegateAuthentication = authentication;
            if (this.authenticationManager != null) {
                DelegatingNegotiateSecurityFilter.LOGGER.debug("Delegating to custom authenticationmanager");
                delegateAuthentication = this.authenticationManager.authenticate(authentication);
            }
            SecurityContextHolder.getContext().setAuthentication(delegateAuthentication);
            if (this.authenticationSuccessHandler != null) {
                try {
                    this.authenticationSuccessHandler.onAuthenticationSuccess(request, response,
                            delegateAuthentication);
                } catch (final IOException | ServletException e) {
                    DelegatingNegotiateSecurityFilter.LOGGER.warn("Error calling authenticationSuccessHandler: {}",
                            e.getMessage());
                    DelegatingNegotiateSecurityFilter.LOGGER.trace("", e);
                    return false;
                }
            }
        } catch (final AuthenticationException e) {
            DelegatingNegotiateSecurityFilter.LOGGER
                    .warn("Error authenticating user in custom authenticationmanager: {}", e.getMessage());
            this.sendAuthenticationFailed(request, response, e);
            return false;
        } catch (final AccessDeniedException e) {
            DelegatingNegotiateSecurityFilter.LOGGER.warn("Error authorizing user in custom authenticationmanager: {}",
                    e.getMessage());
            this.sendAccessDenied(request, response, e);
            return false;
        }
        return true;
    }

    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();

        if (this.getProvider() == null) {
            throw new ServletException("Missing NegotiateSecurityFilter.Provider");
        }
    }

    /**
     * Forward to authenticationFailureHandler.
     *
     * @param request
     *            the request
     * @param response
     *            HTTP Response
     * @param ae
     *            the ae
     */
    private void sendAuthenticationFailed(final HttpServletRequest request, final HttpServletResponse response,
            final AuthenticationException ae) {
        if (this.authenticationFailureHandler != null) {
            try {
                this.authenticationFailureHandler.onAuthenticationFailure(request, response, ae);
                return;
            } catch (final IOException e) {
                DelegatingNegotiateSecurityFilter.LOGGER.warn("IOException invoking authenticationFailureHandler: {}",
                        e.getMessage());
                DelegatingNegotiateSecurityFilter.LOGGER.trace("", e);
            } catch (final ServletException e) {
                DelegatingNegotiateSecurityFilter.LOGGER
                        .warn("ServletException invoking authenticationFailureHandler: {}", e.getMessage());
                DelegatingNegotiateSecurityFilter.LOGGER.trace("", e);
            }
        }
        super.sendUnauthorized(response, true);
    }

    /**
     * Forward to accessDeniedHandler.
     *
     * @param request
     *            the request
     * @param response
     *            HTTP Response
     * @param ae
     *            the ae
     */
    private void sendAccessDenied(final HttpServletRequest request, final HttpServletResponse response,
            final AccessDeniedException ae) {
        if (this.accessDeniedHandler != null) {
            try {
                this.accessDeniedHandler.handle(request, response, ae);
                return;
            } catch (final IOException e) {
                DelegatingNegotiateSecurityFilter.LOGGER.warn("IOException invoking accessDeniedHandler: {}",
                        e.getMessage());
                DelegatingNegotiateSecurityFilter.LOGGER.trace("", e);
            } catch (final ServletException e) {
                DelegatingNegotiateSecurityFilter.LOGGER.warn("ServletException invoking accessDeniedHandler: {}",
                        e.getMessage());
                DelegatingNegotiateSecurityFilter.LOGGER.trace("", e);
            }
        }
        // fallback
        this.sendUnauthorized(response, true);
    }

    /**
     * Gets the authentication success handler.
     *
     * @return the authenticationSuccessHandler
     */
    public AuthenticationSuccessHandler getAuthenticationSuccessHandler() {
        return this.authenticationSuccessHandler;
    }

    /**
     * Sets the authentication success handler.
     *
     * @param value
     *            the authenticationSuccessHandler to set
     */
    public void setAuthenticationSuccessHandler(final AuthenticationSuccessHandler value) {
        this.authenticationSuccessHandler = value;
    }

    /**
     * Gets the authentication manager.
     *
     * @return the authenticationManager
     */
    public AuthenticationManager getAuthenticationManager() {
        return this.authenticationManager;
    }

    /**
     * Sets the authentication manager.
     *
     * @param value
     *            the authenticationManager to set
     */
    public void setAuthenticationManager(final AuthenticationManager value) {
        this.authenticationManager = value;
    }

}
