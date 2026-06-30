package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class SubscribeBookingRequest {
  private String openId;
  private String templateId;
}
