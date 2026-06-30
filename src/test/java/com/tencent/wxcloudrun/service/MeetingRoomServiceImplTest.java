package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ApiErrorCode;
import com.tencent.wxcloudrun.dao.MeetingRoomMapper;
import com.tencent.wxcloudrun.dto.AttendeeRequest;
import com.tencent.wxcloudrun.dto.CreateBookingRequest;
import com.tencent.wxcloudrun.model.Booking;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.service.impl.MeetingRoomServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingRoomServiceImplTest {

  private final MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
  private final MeetingRoomService service = new MeetingRoomServiceImpl(mapper, new ObjectMapper());

  @Test
  void createBookingRejectsOverlappingPendingBooking() {
    User organizer = user("u_001", "openid_001", "张明", "万事网联");
    Room room = room("201", "启航 A");
    Booking existing = booking("b_001", "201", "2026-07-01", "10:00", "11:00", "需求梳理", "万事网联");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(organizer);
    when(mapper.findRoomById("201")).thenReturn(room);
    when(mapper.lockBookingsByRoomAndDate("201", "2026-07-01")).thenReturn(Arrays.asList(existing));

    CreateBookingRequest request = new CreateBookingRequest();
    request.setRoomId("201");
    request.setOrganizerOpenId("openid_001");
    request.setDate("2026-07-01");
    request.setStartTime("10:30");
    request.setEndTime("11:30");
    request.setTitle("接口联调讨论");
    request.setAttendees(Arrays.asList(attendee("u_001", "张明", "万事网联")));

    ApiException exception = assertThrows(ApiException.class, () -> service.createBooking(request));
    assertEquals(ApiErrorCode.BOOKING_CONFLICT, exception.getCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void roomCalendarHidesTitleForDifferentCompany() {
    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    User viewer = user("u_001", "openid_001", "张明", "万事网联");
    Room room = room("201", "启航 A");
    Booking sameCompany = booking("b_001", "201", today, "10:00", "11:00", "需求梳理", "万事网联");
    Booking otherCompany = booking("b_002", "201", today, "14:00", "15:30", "预算复盘", "万事达卡");

    when(mapper.findRoomById("201")).thenReturn(room);
    when(mapper.findUserByOpenId("openid_001")).thenReturn(viewer);
    when(mapper.listBookingsByRoomAndDateRange(anyString(), anyString(), anyString())).thenReturn(Arrays.asList(sameCompany, otherCompany));

    Map<String, Object> data = service.getRoomCalendar("201", today, 1, "openid_001");
    List<Map<String, Object>> days = (List<Map<String, Object>>) data.get("days");
    List<Map<String, Object>> bookings = (List<Map<String, Object>>) days.get(0).get("bookings");

    assertEquals("需求梳理", bookings.get(0).get("displayTitle"));
    assertEquals("已占用", bookings.get(1).get("displayTitle"));
    assertFalse((Boolean) bookings.get(1).get("titleVisible"));
    assertEquals("", bookings.get(1).get("title"));
  }

  private User user(String id, String openId, String name, String company) {
    User user = new User();
    user.setId(id);
    user.setOpenId(openId);
    user.setName(name);
    user.setCompany(company);
    user.setPhone("13800000000");
    user.setEmail("test@example.com");
    return user;
  }

  private Room room(String id, String name) {
    Room room = new Room();
    room.setId(id);
    room.setName(name);
    room.setEnabled(true);
    return room;
  }

  private Booking booking(String id, String roomId, String date, String startTime, String endTime, String title, String company) {
    Booking booking = new Booking();
    booking.setId(id);
    booking.setRoomId(roomId);
    booking.setRoomName("启航 A");
    booking.setDate(date);
    booking.setStartTime(startTime);
    booking.setEndTime(endTime);
    booking.setTitle(title);
    booking.setOrganizerOpenId("openid_001");
    booking.setOrganizerUserId("u_001");
    booking.setOrganizerName("张明");
    booking.setOrganizerCompany(company);
    booking.setStatus("pending");
    return booking;
  }

  private AttendeeRequest attendee(String userId, String name, String company) {
    AttendeeRequest attendee = new AttendeeRequest();
    attendee.setUserId(userId);
    attendee.setName(name);
    attendee.setCompany(company);
    return attendee;
  }
}
