package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.List;

@Data
public class RescheduleBookingRequest {
  private String openId;
  private String organizerOpenId;
  private String roomId;
  private String date;
  private String startTime;
  private String endTime;
  private String title;
  private List<AttendeeRequest> attendees;
}
