package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Room {
  private String id;
  private String name;
  private Integer roomCapacity;
  private Boolean enabled;
  private Integer sortOrder;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
