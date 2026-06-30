package com.tencent.wxcloudrun.service;

import com.tencent.wxcloudrun.dto.CheckUserRequest;
import com.tencent.wxcloudrun.dto.CreateBookingRequest;
import com.tencent.wxcloudrun.dto.InviteVerifyRequest;
import com.tencent.wxcloudrun.dto.RegisterRequest;
import com.tencent.wxcloudrun.dto.RescheduleBookingRequest;

import java.util.Map;

public interface MeetingRoomService {

  Map<String, Object> checkUser(CheckUserRequest request);

  Map<String, Object> verifyInvite(InviteVerifyRequest request);

  Map<String, Object> registerUser(RegisterRequest request);

  Map<String, Object> getProfile(String openId);

  Map<String, Object> getHomeSummary(String openId);

  Map<String, Object> listRooms(String date, String startTime, String endTime, Boolean onlyAvailable);

  Map<String, Object> getRoomStatus(String date);

  Map<String, Object> getRoomCalendar(String roomId, String startDate, Integer days, String viewerOpenId);

  Map<String, Object> searchUsers(String keyword, String viewerOpenId, Integer limit);

  Map<String, Object> createBooking(CreateBookingRequest request);

  Map<String, Object> getMyBookings(String openId, String status);

  Map<String, Object> cancelBooking(String bookingId, String openId);

  Map<String, Object> rescheduleBooking(String bookingId, RescheduleBookingRequest request);
}
