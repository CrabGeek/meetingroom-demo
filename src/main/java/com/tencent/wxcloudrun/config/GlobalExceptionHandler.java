package com.tencent.wxcloudrun.config;

import com.tencent.wxcloudrun.service.ApiException;
import com.tencent.wxcloudrun.service.impl.MeetingRoomServiceImpl.BookingConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BookingConflictException.class)
  public ApiResponse handleBookingConflict(BookingConflictException ex) {
    logger.warn("api.error code={} message={} conflictBookingId={}", ex.getCode(), ex.getMessage(), ex.getConflictBookingId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("conflictBookingId", ex.getConflictBookingId());
    return ApiResponse.fail(ex.getCode(), ex.getMessage(), data);
  }

  @ExceptionHandler(ApiException.class)
  public ApiResponse handleApiException(ApiException ex) {
    logger.warn("api.error code={} message={}", ex.getCode(), ex.getMessage());
    return ApiResponse.fail(ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ApiResponse handleException(Exception ex) {
    logger.error("Unhandled exception", ex);
    return ApiResponse.fail(ApiErrorCode.INTERNAL_ERROR, "服务端异常");
  }
}
