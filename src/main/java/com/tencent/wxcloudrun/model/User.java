package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
  private String id;
  private String openId;
  private String firstName;
  private String lastName;
  private String name;
  private String company;
  private String email;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
