package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class CheckUserRequest {
  private String code;
  private String openId;
}
