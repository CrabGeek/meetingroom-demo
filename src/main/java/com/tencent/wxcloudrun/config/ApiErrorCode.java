package com.tencent.wxcloudrun.config;

public final class ApiErrorCode {

  public static final String UNAUTHORIZED = "UNAUTHORIZED";
  public static final String USER_NOT_REGISTERED = "USER_NOT_REGISTERED";
  public static final String INVALID_INVITE_CODE = "INVALID_INVITE_CODE";
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
  public static final String BOOKING_CONFLICT = "BOOKING_CONFLICT";
  public static final String BOOKING_NOT_FOUND = "BOOKING_NOT_FOUND";
  public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private ApiErrorCode() {
  }
}
