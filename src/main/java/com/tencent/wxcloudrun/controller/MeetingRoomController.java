package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.dto.CheckUserRequest;
import com.tencent.wxcloudrun.dto.CreateBookingRequest;
import com.tencent.wxcloudrun.dto.InviteVerifyRequest;
import com.tencent.wxcloudrun.dto.RegisterRequest;
import com.tencent.wxcloudrun.dto.RescheduleBookingRequest;
import com.tencent.wxcloudrun.service.MeetingRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MeetingRoomController {

  private final MeetingRoomService meetingRoomService;

  public MeetingRoomController(@Autowired MeetingRoomService meetingRoomService) {
    this.meetingRoomService = meetingRoomService;
  }

  @PostMapping("/auth/check-user")
  public ApiResponse checkUser(@RequestBody CheckUserRequest request) {
    return ApiResponse.ok(meetingRoomService.checkUser(request));
  }

  @PostMapping("/invite/verify")
  public ApiResponse verifyInvite(@RequestBody InviteVerifyRequest request) {
    return ApiResponse.ok(meetingRoomService.verifyInvite(request));
  }

  @PostMapping("/users/register")
  public ApiResponse registerUser(@RequestBody RegisterRequest request) {
    return ApiResponse.ok(meetingRoomService.registerUser(request));
  }

  @GetMapping("/users/profile")
  public ApiResponse getProfile(@RequestParam("openId") String openId) {
    return ApiResponse.ok(meetingRoomService.getProfile(openId));
  }

  @GetMapping("/home/summary")
  public ApiResponse getHomeSummary(@RequestParam("openId") String openId) {
    return ApiResponse.ok(meetingRoomService.getHomeSummary(openId));
  }

  @GetMapping("/rooms")
  public ApiResponse listRooms(@RequestParam(value = "date", required = false) String date,
                               @RequestParam(value = "startTime", required = false) String startTime,
                               @RequestParam(value = "endTime", required = false) String endTime,
                               @RequestParam(value = "onlyAvailable", required = false) Boolean onlyAvailable) {
    return ApiResponse.ok(meetingRoomService.listRooms(date, startTime, endTime, onlyAvailable));
  }

  @GetMapping("/rooms/status")
  public ApiResponse getRoomStatus(@RequestParam(value = "date", required = false) String date) {
    return ApiResponse.ok(meetingRoomService.getRoomStatus(date));
  }

  @GetMapping("/rooms/{roomId}/calendar")
  public ApiResponse getRoomCalendar(@PathVariable("roomId") String roomId,
                                     @RequestParam("startDate") String startDate,
                                     @RequestParam(value = "days", required = false) Integer days,
                                     @RequestParam("viewerOpenId") String viewerOpenId) {
    return ApiResponse.ok(meetingRoomService.getRoomCalendar(roomId, startDate, days, viewerOpenId));
  }

  @GetMapping("/users/search")
  public ApiResponse searchUsers(@RequestParam("keyword") String keyword,
                                 @RequestParam("viewerOpenId") String viewerOpenId,
                                 @RequestParam(value = "limit", required = false) Integer limit) {
    return ApiResponse.ok(meetingRoomService.searchUsers(keyword, viewerOpenId, limit));
  }

  @PostMapping("/bookings")
  public ApiResponse createBooking(@RequestBody CreateBookingRequest request) {
    return ApiResponse.ok(meetingRoomService.createBooking(request));
  }

  @GetMapping("/bookings/my")
  public ApiResponse getMyBookings(@RequestParam("openId") String openId,
                                   @RequestParam(value = "status", required = false) String status) {
    return ApiResponse.ok(meetingRoomService.getMyBookings(openId, status));
  }

  @PostMapping("/bookings/{bookingId}/cancel")
  public ApiResponse cancelBooking(@PathVariable("bookingId") String bookingId, @RequestBody Map<String, String> request) {
    return ApiResponse.ok(meetingRoomService.cancelBooking(bookingId, request == null ? null : request.get("openId")));
  }

  @PostMapping("/bookings/{bookingId}/reschedule")
  public ApiResponse rescheduleBooking(@PathVariable("bookingId") String bookingId,
                                       @RequestBody RescheduleBookingRequest request) {
    return ApiResponse.ok(meetingRoomService.rescheduleBooking(bookingId, request));
  }
}
