package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class RescheduleBookingRequest {
  private String openId;
  private String roomId;
  private String date;
  private String startTime;
  private String endTime;
}
