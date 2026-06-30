package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class InviteVerifyRequest {
  private String openId;
  private String inviteCode;
}
