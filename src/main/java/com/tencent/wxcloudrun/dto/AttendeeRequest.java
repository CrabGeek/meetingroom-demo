package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class AttendeeRequest {
  private String userId;
  private String name;
  private String company;
}
