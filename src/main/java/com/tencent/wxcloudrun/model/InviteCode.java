package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InviteCode {
  private String id;
  private String code;
  private Boolean enabled;
  private String companyScope;
  private String usedBy;
  private LocalDateTime expiresAt;
  private LocalDateTime createdAt;
}
