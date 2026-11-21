package com.trailheadgear.orderintegration;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * The last-resort answer of the partner endpoint (design 005 H2): a request still unanswered when
 * {@code spring.mvc.async.request-timeout} (9 s, before NFR-002's 10 s) expires is answered 503 with the contract's
 * temporarily unavailable Problem, built by {@link OrderAnswers} (the order number is unknown and left out). Only for
 * {@code /orders}; any other path gets Spring's default answer. The log line carries no body and no key.
 */
@RestControllerAdvice
public class PartnerTimeoutAnswer {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerTimeoutAnswer.class);
    private static final String PARTNER_PATH = "/orders";

    private final OrderAnswers orderAnswers;

    /** The {@code orderAnswers} bean of {@link IntegrationBeans}, the same the routes answer with. */
    public PartnerTimeoutAnswer(OrderAnswers orderAnswers) {
        this.orderAnswers = orderAnswers;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> answerTimeout(AsyncRequestTimeoutException timeout, HttpServletRequest request)
            throws IOException {
        if (!PARTNER_PATH.equals(request.getRequestURI())) {
            // Rethrown: Spring's default handling answers it
            throw timeout;
        }
        LOG.warn("Partner request answered by the time limit correlationId=-");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Content-Type", orderAnswers.contentType(503))
                .body(orderAnswers.body(503, null, null));
    }
}
