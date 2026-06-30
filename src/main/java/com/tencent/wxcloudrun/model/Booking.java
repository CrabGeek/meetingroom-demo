package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Booking {
  private String id;
  private String roomId;
  private String roomName;
  private String date;
  private String startTime;
  private String endTime;
  private String title;
  private String organizerOpenId;
  private String organizerUserId;
  private String organizerName;
  private String organizerCompany;
  private String attendees;
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
