/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.northbound.utils;

import static java.lang.String.format;
import static org.openkilda.messaging.Utils.CORRELATION_ID;
import static org.openkilda.messaging.Utils.DEFAULT_CORRELATION_ID;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageError;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.northbound.error.NorthboundException;

import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Optional;

/**
 * Common exception handler for controllers.
 */
@ControllerAdvice
public class NorthboundExceptionHandler extends ResponseEntityExceptionHandler {
    /**
     * Handles NorthboundException exception.
     *
     * @param exception the NorthboundException instance
     * @param request   the WebRequest caused exception
     * @return the ResponseEntity object instance
     */
    @ExceptionHandler(MessageException.class)
    protected ResponseEntity<Object> handleMessageException(MessageException exception, WebRequest request) {
        HttpStatus status;

        switch (exception.getErrorType()) {
            case NOT_FOUND:
                status = HttpStatus.NOT_FOUND;
                break;
            case DATA_INVALID:
                status = HttpStatus.BAD_REQUEST;
                break;
            case PARAMETERS_INVALID:
                status = HttpStatus.BAD_REQUEST;
                break;
            case REQUEST_INVALID:
                status = HttpStatus.BAD_REQUEST;
                break;
            case OPERATION_TIMED_OUT:
                status = HttpStatus.REQUEST_TIMEOUT;
                break;
            case ALREADY_EXISTS:
                status = HttpStatus.CONFLICT;
                break;
            case AUTH_FAILED:
                status = HttpStatus.UNAUTHORIZED;
                break;
            case UNPROCESSABLE_REQUEST:
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                break;
            case INTERNAL_ERROR:
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                break;
            case NOT_PERMITTED:
                status = HttpStatus.FORBIDDEN;
                break;
            default:
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                break;
        }

        MessageError error = new MessageError(exception.getCorrelationId(), exception.getTimestamp(),
                exception.getErrorType().toString(), exception.getMessage(), exception.getErrorDescription());

        try (MDCCloseable mdcCloseable = MDC.putCloseable(CORRELATION_ID, exception.getCorrelationId())) {
            logger.warn(format("Error %s caught.", error), exception);
        }

        return super.handleExceptionInternal(exception, error, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(NorthboundException.class)
    protected ResponseEntity<Object> handleMessageException(NorthboundException exception, WebRequest request) {
        return super.handleExceptionInternal(
                exception, exception.getPayload(), new HttpHeaders(), exception.getStatus(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatus status, WebRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(CORRELATION_ID)).orElse(DEFAULT_CORRELATION_ID);
        MessageError error = new MessageError(correlationId, System.currentTimeMillis(),
                ErrorType.REQUEST_INVALID.toString(), exception.getMessage(), exception.getClass().getSimpleName());

        logger.error(format("Unknown error %s caught.", error), exception);

        return super.handleExceptionInternal(exception, error, headers, status, request);
    }
}
