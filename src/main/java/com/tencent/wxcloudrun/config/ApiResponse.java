package com.tencent.wxcloudrun.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.HashMap;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse {

  private Boolean success;
  private String code;
  private String message;
  private Object data;

  private ApiResponse(boolean success, String code, String message, Object data) {
    this.success = success;
    this.code = code;
    this.message = message;
    this.data = data;
  }
  
  public static ApiResponse ok() {
    return new ApiResponse(true, null, "", new HashMap<>());
  }

  public static ApiResponse ok(Object data) {
    return new ApiResponse(true, null, "", data);
  }

  public static ApiResponse error(String errorMsg) {
    return fail(ApiErrorCode.VALIDATION_ERROR, errorMsg);
  }

  public static ApiResponse fail(String code, String message) {
    return fail(code, message, null);
  }

  public static ApiResponse fail(String code, String message, Object data) {
    return new ApiResponse(false, code, message, data);
  }
}
