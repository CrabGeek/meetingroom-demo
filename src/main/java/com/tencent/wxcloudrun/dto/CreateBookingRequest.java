package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateBookingRequest {
  private String roomId;
  private String organizerOpenId;
  private String date;
  private String startTime;
  private String endTime;
  private String title;
  private List<AttendeeRequest> attendees;
}
