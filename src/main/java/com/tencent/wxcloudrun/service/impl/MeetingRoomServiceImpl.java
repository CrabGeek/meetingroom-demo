package com.tencent.wxcloudrun.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ApiErrorCode;
import com.tencent.wxcloudrun.dao.MeetingRoomMapper;
import com.tencent.wxcloudrun.dto.AttendeeRequest;
import com.tencent.wxcloudrun.dto.CheckUserRequest;
import com.tencent.wxcloudrun.dto.CreateBookingRequest;
import com.tencent.wxcloudrun.dto.InviteVerifyRequest;
import com.tencent.wxcloudrun.dto.RegisterRequest;
import com.tencent.wxcloudrun.dto.RescheduleBookingRequest;
import com.tencent.wxcloudrun.model.Booking;
import com.tencent.wxcloudrun.model.InviteCode;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.service.ApiException;
import com.tencent.wxcloudrun.service.MeetingRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MeetingRoomServiceImpl implements MeetingRoomService {

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("^\\d{6}$");
  private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final String COMPANY_A = "万事网联";
  private static final String COMPANY_B = "万事达卡";
  private static final String STATUS_PENDING = "pending";
  private static final String STATUS_CANCELLED = "cancelled";
  private static final String DISPLAY_OCCUPIED = "已占用";

  private final MeetingRoomMapper meetingRoomMapper;
  private final ObjectMapper objectMapper;

  @Value("${wx.app-id:}")
  private String wxAppId;

  @Value("${wx.app-secret:}")
  private String wxAppSecret;

  public MeetingRoomServiceImpl(@Autowired MeetingRoomMapper meetingRoomMapper, @Autowired ObjectMapper objectMapper) {
    this.meetingRoomMapper = meetingRoomMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public Map<String, Object> checkUser(CheckUserRequest request) {
    if (request == null) {
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "缺少登录参数");
    }
    String openId = resolveOpenId(request);
    User user = meetingRoomMapper.findUserByOpenId(openId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("registered", user != null);
    data.put("openId", openId);
    data.put("user", user == null ? null : userToMap(user, true));
    return data;
  }

  @Override
  public Map<String, Object> verifyInvite(InviteVerifyRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getInviteCode())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "openId 和 inviteCode 不能为空");
    }
    if (!INVITE_CODE_PATTERN.matcher(request.getInviteCode()).matches()) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "邀请码必须为 6 位数字");
    }

    InviteCode inviteCode = meetingRoomMapper.findInviteByCode(request.getInviteCode());
    if (!isInviteUsable(inviteCode)) {
      throw new ApiException(ApiErrorCode.INVALID_INVITE_CODE, "邀请码错误或已失效");
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("valid", true);
    data.put("inviteId", inviteCode.getId());
    data.put("companyScope", parseStringList(inviteCode.getCompanyScope()));
    return data;
  }

  @Override
  @Transactional
  public Map<String, Object> registerUser(RegisterRequest request) {
    validateRegisterRequest(request);
    InviteCode inviteCode = null;
    if (!isBlank(request.getInviteId())) {
      inviteCode = meetingRoomMapper.findInviteById(request.getInviteId());
      if (!isInviteUsable(inviteCode)) {
        throw new ApiException(ApiErrorCode.INVALID_INVITE_CODE, "邀请码错误或已失效");
      }
      List<String> companyScope = parseStringList(inviteCode.getCompanyScope());
      if (!companyScope.isEmpty() && !companyScope.contains(request.getCompany())) {
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "公司不在邀请码允许范围内");
      }
    }

    User user = meetingRoomMapper.findUserByOpenId(request.getOpenId());
    if (user == null) {
      user = new User();
      user.setId(newId("u"));
      user.setOpenId(request.getOpenId());
      user.setName(request.getName().trim());
      user.setCompany(request.getCompany());
      user.setPhone(request.getPhone().trim());
      user.setEmail(request.getEmail().trim());
      meetingRoomMapper.insertUser(user);
    } else {
      user.setName(request.getName().trim());
      user.setCompany(request.getCompany());
      user.setPhone(request.getPhone().trim());
      user.setEmail(request.getEmail().trim());
      meetingRoomMapper.updateUser(user);
    }

    if (inviteCode != null) {
      markInviteUsed(inviteCode, request.getOpenId());
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("user", userToMap(meetingRoomMapper.findUserByOpenId(request.getOpenId()), true));
    return data;
  }

  @Override
  public Map<String, Object> getProfile(String openId) {
    User user = requireUser(openId);
    return userToMap(user, false);
  }

  @Override
  public Map<String, Object> getHomeSummary(String openId) {
    User user = requireUser(openId);
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("name", user.getName());
    profile.put("company", user.getCompany());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profile", profile);
    data.put("pendingBookingCount", meetingRoomMapper.countPendingBookingsByOrganizer(openId));
    return data;
  }

  @Override
  public Map<String, Object> listRooms(String date, String startTime, String endTime, Boolean onlyAvailable) {
    boolean hasTimeQuery = !isBlank(date) || !isBlank(startTime) || !isBlank(endTime);
    LocalDate queryDate = null;
    LocalTime queryStart = null;
    LocalTime queryEnd = null;
    if (hasTimeQuery) {
      if (isBlank(date) || isBlank(startTime) || isBlank(endTime)) {
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "date/startTime/endTime 必须同时提供");
      }
      queryDate = parseDate(date);
      queryStart = parseTime(startTime);
      queryEnd = parseTime(endTime);
      validateTimeRange(queryStart, queryEnd);
    }

    LocalDate today = LocalDate.now();
    LocalTime now = LocalTime.now();
    Map<String, Booking> activeBookings = activeBookingMap(today.format(DATE_FORMATTER), now.format(TIME_FORMATTER));
    List<Map<String, Object>> roomMaps = new ArrayList<>();
    for (Room room : meetingRoomMapper.listEnabledRooms()) {
      boolean available = true;
      if (hasTimeQuery) {
        available = !hasConflict(meetingRoomMapper.listBookingsByRoomAndDate(room.getId(), queryDate.format(DATE_FORMATTER)), queryStart, queryEnd, null);
      }
      if (Boolean.TRUE.equals(onlyAvailable) && !available) {
        continue;
      }
      roomMaps.add(roomStatusMap(room, activeBookings.get(room.getId()), available));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rooms", roomMaps);
    return data;
  }

  @Override
  public Map<String, Object> getRoomStatus(String date) {
    String statusDate = isBlank(date) ? LocalDate.now().format(DATE_FORMATTER) : parseDate(date).format(DATE_FORMATTER);
    String now = LocalTime.now().format(TIME_FORMATTER);
    Map<String, Booking> activeBookings = activeBookingMap(statusDate, now);

    int availableCount = 0;
    int busyCount = 0;
    List<Map<String, Object>> rooms = new ArrayList<>();
    for (Room room : meetingRoomMapper.listEnabledRooms()) {
      Booking activeBooking = activeBookings.get(room.getId());
      if (activeBooking == null) {
        availableCount++;
      } else {
        busyCount++;
      }
      rooms.add(roomStatusMap(room, activeBooking, activeBooking == null));
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("available", availableCount);
    summary.put("busy", busyCount);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("rooms", rooms);
    return data;
  }

  @Override
  public Map<String, Object> getRoomCalendar(String roomId, String startDate, Integer days, String viewerOpenId) {
    if (isBlank(roomId) || isBlank(startDate) || isBlank(viewerOpenId)) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "roomId/startDate/viewerOpenId 不能为空");
    }
    Room room = meetingRoomMapper.findRoomById(roomId);
    if (room == null) {
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    User viewer = requireUser(viewerOpenId);
    int dayCount = days == null ? 5 : days;
    if (dayCount < 1 || dayCount > 5) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "days 当前仅支持 1 到 5 天");
    }
    LocalDate firstDate = parseDate(startDate);
    LocalDate today = LocalDate.now();
    if (firstDate.isAfter(today.plusDays(14))) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "日历最多查看当前日期起两周内数据");
    }
    LocalDate endDate = firstDate.plusDays(dayCount - 1L);
    List<Booking> bookings = meetingRoomMapper.listBookingsByRoomAndDateRange(roomId, firstDate.format(DATE_FORMATTER), endDate.format(DATE_FORMATTER));
    Map<String, List<Booking>> bookingsByDate = groupBookingsByDate(bookings);

    List<Map<String, Object>> daysData = new ArrayList<>();
    for (int index = 0; index < dayCount; index++) {
      LocalDate current = firstDate.plusDays(index);
      String currentDate = current.format(DATE_FORMATTER);
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("date", currentDate);
      day.put("weekday", weekdayText(current));
      day.put("bookings", calendarBookings(bookingsByDate.get(currentDate), viewer.getCompany()));
      daysData.add(day);
    }

    Map<String, Object> roomData = new LinkedHashMap<>();
    roomData.put("id", room.getId());
    roomData.put("name", room.getName());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("room", roomData);
    data.put("days", daysData);
    return data;
  }

  @Override
  public Map<String, Object> searchUsers(String keyword, String viewerOpenId, Integer limit) {
    requireUser(viewerOpenId);
    if (isBlank(keyword)) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("users", new ArrayList<Map<String, Object>>());
      return data;
    }
    int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
    List<Map<String, Object>> users = new ArrayList<>();
    for (User user : meetingRoomMapper.searchUsers(keyword.trim(), safeLimit)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", user.getId());
      item.put("openId", user.getOpenId());
      item.put("name", user.getName());
      item.put("company", user.getCompany());
      item.put("email", user.getEmail());
      users.add(item);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("users", users);
    return data;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Map<String, Object> createBooking(CreateBookingRequest request) {
    validateCreateBookingRequest(request);
    User organizer = requireUser(request.getOrganizerOpenId());
    Room room = meetingRoomMapper.findRoomById(request.getRoomId());
    if (room == null) {
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    LocalTime start = parseTime(request.getStartTime());
    LocalTime end = parseTime(request.getEndTime());
    validateTimeRange(start, end);

    List<Booking> lockedBookings = meetingRoomMapper.lockBookingsByRoomAndDate(request.getRoomId(), request.getDate());
    Booking conflict = findConflict(lockedBookings, start, end, null);
    if (conflict != null) {
      throw bookingConflict(conflict.getId());
    }

    List<Map<String, Object>> attendees = normalizeAttendees(request.getAttendees(), organizer);
    Booking booking = new Booking();
    booking.setId(newId("b"));
    booking.setRoomId(room.getId());
    booking.setRoomName(room.getName());
    booking.setDate(request.getDate());
    booking.setStartTime(request.getStartTime());
    booking.setEndTime(request.getEndTime());
    booking.setTitle(request.getTitle().trim());
    booking.setOrganizerOpenId(organizer.getOpenId());
    booking.setOrganizerUserId(organizer.getId());
    booking.setOrganizerName(organizer.getName());
    booking.setOrganizerCompany(organizer.getCompany());
    booking.setAttendees(toJson(attendees));
    booking.setStatus(STATUS_PENDING);
    meetingRoomMapper.insertBooking(booking);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("booking", bookingToSummaryMap(meetingRoomMapper.findBookingById(booking.getId())));
    return data;
  }

  @Override
  public Map<String, Object> getMyBookings(String openId, String status) {
    requireUser(openId);
    String queryStatus = isBlank(status) ? STATUS_PENDING : status;
    List<Map<String, Object>> bookings = new ArrayList<>();
    for (Booking booking : meetingRoomMapper.listMyBookings(openId, queryStatus)) {
      bookings.add(bookingToListMap(booking));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bookings", bookings);
    return data;
  }

  @Override
  @Transactional
  public Map<String, Object> cancelBooking(String bookingId, String openId) {
    requireUser(openId);
    Booking booking = meetingRoomMapper.findBookingById(bookingId);
    if (booking == null) {
      throw new ApiException(ApiErrorCode.BOOKING_NOT_FOUND, "预约记录不存在");
    }
    if (!openId.equals(booking.getOrganizerOpenId())) {
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限取消该预约");
    }
    if (!STATUS_PENDING.equals(booking.getStatus())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前预约状态不允许取消");
    }
    meetingRoomMapper.cancelBooking(bookingId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bookingId", bookingId);
    data.put("status", STATUS_CANCELLED);
    return data;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Map<String, Object> rescheduleBooking(String bookingId, RescheduleBookingRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getRoomId()) || isBlank(request.getDate())
        || isBlank(request.getStartTime()) || isBlank(request.getEndTime())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "改期参数不完整");
    }
    requireUser(request.getOpenId());
    Booking booking = meetingRoomMapper.findBookingById(bookingId);
    if (booking == null) {
      throw new ApiException(ApiErrorCode.BOOKING_NOT_FOUND, "预约记录不存在");
    }
    if (!request.getOpenId().equals(booking.getOrganizerOpenId())) {
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限改期该预约");
    }
    if (!STATUS_PENDING.equals(booking.getStatus())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前预约状态不允许改期");
    }
    Room room = meetingRoomMapper.findRoomById(request.getRoomId());
    if (room == null) {
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    parseDate(request.getDate());
    LocalTime start = parseTime(request.getStartTime());
    LocalTime end = parseTime(request.getEndTime());
    validateTimeRange(start, end);

    List<Booking> lockedBookings = meetingRoomMapper.lockBookingsByRoomAndDate(request.getRoomId(), request.getDate());
    Booking conflict = findConflict(lockedBookings, start, end, bookingId);
    if (conflict != null) {
      throw bookingConflict(conflict.getId());
    }

    booking.setRoomId(room.getId());
    booking.setRoomName(room.getName());
    booking.setDate(request.getDate());
    booking.setStartTime(request.getStartTime());
    booking.setEndTime(request.getEndTime());
    meetingRoomMapper.updateBookingSchedule(booking);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("booking", bookingToSummaryMap(meetingRoomMapper.findBookingById(bookingId)));
    return data;
  }

  private String resolveOpenId(CheckUserRequest request) {
    if (!isBlank(request.getOpenId())) {
      return request.getOpenId().trim();
    }
    if (isBlank(request.getCode())) {
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "code 不能为空");
    }
    if (isBlank(wxAppId) || isBlank(wxAppSecret)) {
      return request.getCode().trim();
    }
    try {
      String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + wxAppId
          + "&secret=" + wxAppSecret
          + "&js_code=" + request.getCode().trim()
          + "&grant_type=authorization_code";
      String body = new RestTemplate().getForObject(url, String.class);
      JsonNode json = objectMapper.readTree(body);
      if (json.hasNonNull("openid")) {
        return json.get("openid").asText();
      }
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, json.has("errmsg") ? json.get("errmsg").asText() : "微信登录失败");
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "微信登录失败");
    }
  }

  private void validateRegisterRequest(RegisterRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getName()) || isBlank(request.getCompany())
        || isBlank(request.getPhone()) || isBlank(request.getEmail())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "注册信息不完整");
    }
    String name = request.getName().trim();
    if (name.length() < 2 || name.length() > 30) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "姓名长度需为 2-30 个字符");
    }
    if (!COMPANY_A.equals(request.getCompany()) && !COMPANY_B.equals(request.getCompany())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "公司不合法");
    }
    if (!PHONE_PATTERN.matcher(request.getPhone().trim()).matches()) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "手机号格式不正确");
    }
    if (!EMAIL_PATTERN.matcher(request.getEmail().trim()).matches()) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "邮箱格式不正确");
    }
  }

  private void validateCreateBookingRequest(CreateBookingRequest request) {
    if (request == null || isBlank(request.getRoomId()) || isBlank(request.getOrganizerOpenId()) || isBlank(request.getDate())
        || isBlank(request.getStartTime()) || isBlank(request.getEndTime()) || isBlank(request.getTitle())
        || request.getAttendees() == null || request.getAttendees().isEmpty()) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "预约参数不完整");
    }
    parseDate(request.getDate());
  }

  private boolean isInviteUsable(InviteCode inviteCode) {
    return inviteCode != null
        && Boolean.TRUE.equals(inviteCode.getEnabled())
        && (inviteCode.getExpiresAt() == null || inviteCode.getExpiresAt().isAfter(LocalDateTime.now()));
  }

  private User requireUser(String openId) {
    if (isBlank(openId)) {
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "openId 不能为空");
    }
    User user = meetingRoomMapper.findUserByOpenId(openId.trim());
    if (user == null) {
      throw new ApiException(ApiErrorCode.USER_NOT_REGISTERED, "用户未注册");
    }
    return user;
  }

  private LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value, DATE_FORMATTER);
    } catch (DateTimeParseException ex) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "日期格式应为 YYYY-MM-DD");
    }
  }

  private LocalTime parseTime(String value) {
    try {
      return LocalTime.parse(value, TIME_FORMATTER);
    } catch (DateTimeParseException ex) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "时间格式应为 HH:mm");
    }
  }

  private void validateTimeRange(LocalTime start, LocalTime end) {
    if (!end.isAfter(start)) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "结束时间必须晚于开始时间");
    }
  }

  private Map<String, Booking> activeBookingMap(String date, String time) {
    Map<String, Booking> activeBookings = new HashMap<>();
    for (Booking booking : meetingRoomMapper.listActiveBookingsByDateTime(date, time)) {
      activeBookings.put(booking.getRoomId(), booking);
    }
    return activeBookings;
  }

  private Map<String, Object> roomStatusMap(Room room, Booking activeBooking, boolean available) {
    boolean busy = activeBooking != null;
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", room.getId());
    item.put("name", room.getName());
    item.put("status", busy ? "busy" : "available");
    item.put("statusText", busy ? "使用中" : "可预订");
    item.put("nextInfo", busy ? "使用中至 " + activeBooking.getEndTime() : "现在可立即预定");
    item.put("available", available);
    return item;
  }

  private boolean hasConflict(List<Booking> bookings, LocalTime start, LocalTime end, String excludeBookingId) {
    return findConflict(bookings, start, end, excludeBookingId) != null;
  }

  private Booking findConflict(List<Booking> bookings, LocalTime start, LocalTime end, String excludeBookingId) {
    for (Booking booking : bookings) {
      if (excludeBookingId != null && excludeBookingId.equals(booking.getId())) {
        continue;
      }
      LocalTime existingStart = parseTime(booking.getStartTime());
      LocalTime existingEnd = parseTime(booking.getEndTime());
      if (end.isAfter(existingStart) && start.isBefore(existingEnd)) {
        return booking;
      }
    }
    return null;
  }

  private List<Map<String, Object>> normalizeAttendees(List<AttendeeRequest> requestAttendees, User organizer) {
    List<Map<String, Object>> attendees = new ArrayList<>();
    Set<String> userIds = new HashSet<>();
    boolean containsOrganizer = false;
    for (AttendeeRequest attendee : requestAttendees) {
      if (attendee == null || isBlank(attendee.getUserId()) || isBlank(attendee.getName()) || isBlank(attendee.getCompany())) {
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "参会人员信息不完整");
      }
      User user = meetingRoomMapper.findUserById(attendee.getUserId());
      if (user == null) {
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "参会人员不存在");
      }
      if (!userIds.add(user.getId())) {
        continue;
      }
      if (user.getId().equals(organizer.getId())) {
        containsOrganizer = true;
      }
      attendees.add(attendeeMap(user));
    }
    if (!containsOrganizer) {
      attendees.add(0, attendeeMap(organizer));
    }
    return attendees;
  }

  private Map<String, Object> attendeeMap(User user) {
    Map<String, Object> attendee = new LinkedHashMap<>();
    attendee.put("userId", user.getId());
    attendee.put("name", user.getName());
    attendee.put("company", user.getCompany());
    return attendee;
  }

  private Map<String, List<Booking>> groupBookingsByDate(List<Booking> bookings) {
    Map<String, List<Booking>> grouped = new HashMap<>();
    for (Booking booking : bookings) {
      if (!grouped.containsKey(booking.getDate())) {
        grouped.put(booking.getDate(), new ArrayList<Booking>());
      }
      grouped.get(booking.getDate()).add(booking);
    }
    return grouped;
  }

  private List<Map<String, Object>> calendarBookings(List<Booking> bookings, String viewerCompany) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (bookings == null) {
      return result;
    }
    for (Booking booking : bookings) {
      boolean titleVisible = viewerCompany.equals(booking.getOrganizerCompany());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", booking.getId());
      item.put("start", booking.getStartTime());
      item.put("end", booking.getEndTime());
      item.put("titleVisible", titleVisible);
      item.put("title", titleVisible ? booking.getTitle() : "");
      item.put("displayTitle", titleVisible ? booking.getTitle() : DISPLAY_OCCUPIED);
      item.put("organizerCompany", booking.getOrganizerCompany());
      result.add(item);
    }
    return result;
  }

  private Map<String, Object> userToMap(User user, boolean includeOpenId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", user.getId());
    if (includeOpenId) {
      data.put("openId", user.getOpenId());
    }
    data.put("name", user.getName());
    data.put("company", user.getCompany());
    data.put("phone", user.getPhone());
    data.put("email", user.getEmail());
    if (user.getCreatedAt() != null) {
      data.put("createdAt", user.getCreatedAt().toString());
    }
    return data;
  }

  private Map<String, Object> bookingToSummaryMap(Booking booking) {
    Map<String, Object> data = bookingToListMap(booking);
    data.put("organizerCompany", booking.getOrganizerCompany());
    return data;
  }

  private Map<String, Object> bookingToListMap(Booking booking) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", booking.getId());
    data.put("title", booking.getTitle());
    data.put("roomId", booking.getRoomId());
    data.put("roomName", booking.getRoomName());
    data.put("date", booking.getDate());
    data.put("startTime", booking.getStartTime());
    data.put("endTime", booking.getEndTime());
    data.put("status", booking.getStatus());
    return data;
  }

  private ApiException bookingConflict(String conflictBookingId) {
    return new BookingConflictException(conflictBookingId);
  }

  private void markInviteUsed(InviteCode inviteCode, String openId) {
    List<String> usedBy = parseStringList(inviteCode.getUsedBy());
    if (!usedBy.contains(openId)) {
      usedBy.add(openId);
      meetingRoomMapper.updateInviteUsedBy(inviteCode.getId(), toJson(usedBy));
    }
  }

  private List<String> parseStringList(String value) {
    if (isBlank(value)) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (Exception ex) {
      return new ArrayList<>();
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "JSON 序列化失败");
    }
  }

  private String weekdayText(LocalDate date) {
    String[] weekdays = new String[] {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    return weekdays[date.getDayOfWeek().getValue() - 1];
  }

  private String newId(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public static class BookingConflictException extends ApiException {
    private final String conflictBookingId;

    public BookingConflictException(String conflictBookingId) {
      super(ApiErrorCode.BOOKING_CONFLICT, "该时间段已被预定");
      this.conflictBookingId = conflictBookingId;
    }

    public String getConflictBookingId() {
      return conflictBookingId;
    }
  }
}
