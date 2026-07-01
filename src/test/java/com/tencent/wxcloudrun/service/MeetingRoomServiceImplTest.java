package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ApiErrorCode;
import com.tencent.wxcloudrun.dao.MeetingRoomMapper;
import com.tencent.wxcloudrun.dto.AttendeeRequest;
import com.tencent.wxcloudrun.dto.CheckUserRequest;
import com.tencent.wxcloudrun.dto.CreateBookingRequest;
import com.tencent.wxcloudrun.dto.RegisterRequest;
import com.tencent.wxcloudrun.dto.SubscribeBookingRequest;
import com.tencent.wxcloudrun.model.Booking;
import com.tencent.wxcloudrun.model.BookingSubscription;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.service.impl.MeetingRoomServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingRoomServiceImplTest {

  private final MeetingRoomMapper mapper = mock(MeetingRoomMapper.class);
  private final MeetingRoomService service = new MeetingRoomServiceImpl(mapper, new ObjectMapper());

  @Test
  void checkUserUsesStableDevOpenIdWhenWechatConfigIsMissing() {
    ReflectionTestUtils.setField(service, "wxDevOpenId", "local_dev_openid");
    User user = user("u_001", "local_dev_openid", "张明", "万事网联");
    when(mapper.findUserByOpenId("local_dev_openid")).thenReturn(user);

    CheckUserRequest request = new CheckUserRequest();
    request.setCode("temporary_login_code");
    request.setOpenId("client_supplied_openid");

    Map<String, Object> data = service.checkUser(request);

    assertEquals("local_dev_openid", data.get("openId"));
    assertEquals(true, data.get("registered"));
  }

  @Test
  void checkUserRejectsClientOpenIdWithoutCodeWhenWechatConfigExists() {
    ReflectionTestUtils.setField(service, "wxAppId", "wx_test_app_id");
    ReflectionTestUtils.setField(service, "wxAppSecret", "wx_test_secret");

    CheckUserRequest request = new CheckUserRequest();
    request.setOpenId("client_supplied_openid");

    ApiException exception = assertThrows(ApiException.class, () -> service.checkUser(request));
    assertEquals(ApiErrorCode.UNAUTHORIZED, exception.getCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void registerUserStoresFirstNameLastNameAndGeneratedName() {
    RegisterRequest request = new RegisterRequest();
    request.setOpenId("openid_001");
    request.setFirstName("明");
    request.setLastName("张");
    request.setName("client_supplied_name");
    request.setCompany("万事网联");
    request.setEmail("zhangming@example.com");

    User savedUser = user("u_001", "openid_001", "张明", "万事网联");
    savedUser.setFirstName("明");
    savedUser.setLastName("张");
    savedUser.setEmail("zhangming@example.com");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(null, savedUser);

    Map<String, Object> data = service.registerUser(request);
    Map<String, Object> user = (Map<String, Object>) data.get("user");

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(mapper).insertUser(userCaptor.capture());
    assertEquals("明", userCaptor.getValue().getFirstName());
    assertEquals("张", userCaptor.getValue().getLastName());
    assertEquals("张明", userCaptor.getValue().getName());
    assertEquals("明", user.get("firstName"));
    assertEquals("张", user.get("lastName"));
    assertEquals("张明", user.get("name"));
  }

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
  void createBookingRejectsDifferentCompanyAttendee() {
    User organizer = user("u_001", "openid_001", "张明", "万事网联");
    User otherCompanyUser = user("u_002", "openid_002", "李雷", "万事达卡");
    Room room = room("201", "启航 A");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(organizer);
    when(mapper.findRoomById("201")).thenReturn(room);
    when(mapper.lockBookingsByRoomAndDate("201", "2026-07-01")).thenReturn(Arrays.asList());
    when(mapper.findUserById("u_002")).thenReturn(otherCompanyUser);

    CreateBookingRequest request = new CreateBookingRequest();
    request.setRoomId("201");
    request.setOrganizerOpenId("openid_001");
    request.setDate("2026-07-01");
    request.setStartTime("10:00");
    request.setEndTime("11:00");
    request.setTitle("接口联调讨论");
    request.setAttendees(Arrays.asList(attendee("u_002", "李雷", "万事达卡")));

    ApiException exception = assertThrows(ApiException.class, () -> service.createBooking(request));
    assertEquals(ApiErrorCode.VALIDATION_ERROR, exception.getCode());
  }

  @Test
  void createBookingRejectsAttendeeCountOverRoomCapacity() {
    User organizer = user("u_001", "openid_001", "张明", "万事网联");
    User attendeeUser = user("u_003", "openid_003", "张华", "万事网联");
    Room room = room("201", "启航 A");
    room.setRoomCapacity(1);

    when(mapper.findUserByOpenId("openid_001")).thenReturn(organizer);
    when(mapper.findRoomById("201")).thenReturn(room);
    when(mapper.lockBookingsByRoomAndDate("201", "2026-07-01")).thenReturn(Arrays.asList());
    when(mapper.findUserById("u_003")).thenReturn(attendeeUser);

    CreateBookingRequest request = new CreateBookingRequest();
    request.setRoomId("201");
    request.setOrganizerOpenId("openid_001");
    request.setDate("2026-07-01");
    request.setStartTime("10:00");
    request.setEndTime("11:00");
    request.setTitle("接口联调讨论");
    request.setAttendees(Arrays.asList(attendee("u_003", "张华", "万事网联")));

    ApiException exception = assertThrows(ApiException.class, () -> service.createBooking(request));
    assertEquals(ApiErrorCode.VALIDATION_ERROR, exception.getCode());
  }

  @Test
  @SuppressWarnings("unchecked")
  void listRoomsIncludesRoomCapacity() {
    Room room = room("201", "启航 A");
    when(mapper.listActiveBookingsByDateTime(anyString(), anyString())).thenReturn(Arrays.asList());
    when(mapper.listEnabledRooms()).thenReturn(Arrays.asList(room));

    Map<String, Object> data = service.listRooms(null, null, null, null);
    List<Map<String, Object>> rooms = (List<Map<String, Object>>) data.get("rooms");

    assertEquals(1, rooms.size());
    assertEquals("201", rooms.get(0).get("id"));
    assertEquals(6, rooms.get(0).get("roomCapacity"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void roomStatusIncludesRoomCapacity() {
    Room room = room("201", "启航 A");
    when(mapper.listActiveBookingsByDateTime(anyString(), anyString())).thenReturn(Arrays.asList());
    when(mapper.listEnabledRooms()).thenReturn(Arrays.asList(room));

    Map<String, Object> data = service.getRoomStatus(null);
    List<Map<String, Object>> rooms = (List<Map<String, Object>>) data.get("rooms");

    assertEquals(1, rooms.size());
    assertEquals("201", rooms.get(0).get("id"));
    assertEquals(6, rooms.get(0).get("roomCapacity"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchUsersOnlySearchesViewerCompany() {
    User viewer = user("u_001", "openid_001", "张明", "万事网联");
    User sameCompanyUser = user("u_003", "openid_003", "张华", "万事网联");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(viewer);
    when(mapper.searchUsers("张", "万事网联", 10)).thenReturn(Arrays.asList(sameCompanyUser));

    Map<String, Object> data = service.searchUsers(" 张 ", "openid_001", null);
    List<Map<String, Object>> users = (List<Map<String, Object>>) data.get("users");

    assertEquals(1, users.size());
    assertEquals("u_003", users.get(0).get("id"));
    assertEquals("万事网联", users.get(0).get("company"));
    verify(mapper).searchUsers("张", "万事网联", 10);
  }

  @Test
  void subscribeBookingStoresOrganizerSubscription() {
    User organizer = user("u_001", "openid_001", "张明", "万事网联");
    Booking booking = booking("b_001", "201", "2026-07-01", "10:00", "11:00", "需求梳理", "万事网联");
    booking.setOrganizerOpenId("openid_001");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(organizer);
    when(mapper.findBookingById("b_001")).thenReturn(booking);

    SubscribeBookingRequest request = new SubscribeBookingRequest();
    request.setOpenId("openid_001");
    request.setTemplateId("DnGf1LKDdeVoIOcD9U16RCQzWBqKnUH_Rll13_cFaLk");

    Map<String, Object> data = service.subscribeBooking("b_001", request);

    assertEquals("b_001", data.get("bookingId"));
    assertEquals(true, data.get("subscribed"));
    verify(mapper).upsertBookingSubscription(any(BookingSubscription.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void getMyBookingsIncludesOrganizerAndAttendeeRoles() {
    User currentUser = user("u_001", "openid_001", "张明", "万事网联");
    Booking organizerBooking = booking("b_001", "201", "2026-07-01", "10:00", "11:00", "产品评审会", "万事网联");
    organizerBooking.setOrganizerOpenId("openid_001");
    organizerBooking.setAttendees("[{\"userId\":\"u_001\",\"name\":\"张明\",\"company\":\"万事网联\"}]");
    Booking attendeeBooking = booking("b_002", "202", "2026-07-01", "14:00", "15:00", "客户沟通", "万事达卡");
    attendeeBooking.setOrganizerOpenId("openid_002");
    attendeeBooking.setAttendees("[{\"userId\":\"u_001\",\"name\":\"张明\",\"company\":\"万事网联\"}]");

    when(mapper.findUserByOpenId("openid_001")).thenReturn(currentUser);
    when(mapper.listMyBookings("openid_001", "pending")).thenReturn(Arrays.asList(organizerBooking));
    when(mapper.listAttendeeBookings("u_001", "pending")).thenReturn(Arrays.asList(organizerBooking, attendeeBooking));

    Map<String, Object> data = service.getMyBookings("openid_001", "pending", null);
    List<Map<String, Object>> bookings = (List<Map<String, Object>>) data.get("bookings");

    assertEquals(2, bookings.size());
    assertEquals("b_001", bookings.get(0).get("id"));
    assertEquals("organizer", bookings.get(0).get("userRole"));
    assertEquals(true, bookings.get(0).get("canManage"));
    List<Map<String, Object>> organizerAttendees = (List<Map<String, Object>>) bookings.get(0).get("attendees");
    assertEquals(1, organizerAttendees.size());
    assertEquals("张明", organizerAttendees.get(0).get("name"));
    assertEquals("b_002", bookings.get(1).get("id"));
    assertEquals("attendee", bookings.get(1).get("userRole"));
    assertEquals(false, bookings.get(1).get("canManage"));
    List<Map<String, Object>> attendeeAttendees = (List<Map<String, Object>>) bookings.get(1).get("attendees");
    assertEquals(1, attendeeAttendees.size());
    assertEquals("张明", attendeeAttendees.get(0).get("name"));
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
    assertEquals("张明", bookings.get(0).get("organizerDisplayName"));
    assertEquals("已占用", bookings.get(1).get("displayTitle"));
    assertEquals("李雷", bookings.get(1).get("organizerDisplayName"));
    assertFalse((Boolean) bookings.get(1).get("titleVisible"));
    assertEquals("", bookings.get(1).get("title"));
  }

  private User user(String id, String openId, String name, String company) {
    User user = new User();
    user.setId(id);
    user.setOpenId(openId);
    user.setFirstName(name.length() > 1 ? name.substring(1) : name);
    user.setLastName(name.length() > 1 ? name.substring(0, 1) : "");
    user.setName(name);
    user.setCompany(company);
    user.setEmail("test@example.com");
    return user;
  }

  private Room room(String id, String name) {
    Room room = new Room();
    room.setId(id);
    room.setName(name);
    room.setRoomCapacity(6);
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
    booking.setOrganizerName("万事网联".equals(company) ? "张明" : "李雷");
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
