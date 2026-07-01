package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class RegisterRequest {
  private String openId;
  private String inviteId;
  private String firstName;
  private String lastName;
  private String name;
  private String company;
  private String email;
}
