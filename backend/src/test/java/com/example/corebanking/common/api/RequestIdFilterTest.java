package com.example.corebanking.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("should generate new UUID when X-Request-Id is absent")
    void doFilter_noHeader_generatesRequestIdAndSetsMdcAndResponseHeader()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValueInsideChain = new AtomicReference<>();

        FilterChain filterChain =
                (req, res) -> {
                    mdcValueInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY));
                };

        filter.doFilter(request, response, filterChain);

        String responseHeader = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(responseHeader).isNotBlank();
        assertThat(mdcValueInsideChain.get()).isEqualTo(responseHeader);
        assertThat(request.getAttribute(RequestIdFilter.MDC_KEY)).isEqualTo(responseHeader);
        // MDC must be cleared after filter completion
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("should preserve existing X-Request-Id header from client")
    void doFilter_existingHeader_preservesRequestId() throws ServletException, IOException {
        String existingId = "client-trace-12345";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, existingId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValueInsideChain = new AtomicReference<>();

        FilterChain filterChain =
                (req, res) -> {
                    mdcValueInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY));
                };

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(existingId);
        assertThat(mdcValueInsideChain.get()).isEqualTo(existingId);
        assertThat(request.getAttribute(RequestIdFilter.MDC_KEY)).isEqualTo(existingId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("should clear MDC even when filter chain throws exception")
    void doFilter_chainThrowsException_clearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain =
                (req, res) -> {
                    throw new ServletException("Simulated filter failure");
                };

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isInstanceOf(ServletException.class)
                .hasMessage("Simulated filter failure");

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
