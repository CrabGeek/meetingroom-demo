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
import com.tencent.wxcloudrun.dto.SubscribeBookingRequest;
import com.tencent.wxcloudrun.model.Booking;
import com.tencent.wxcloudrun.model.BookingSubscription;
import com.tencent.wxcloudrun.model.InviteCode;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.service.ApiException;
import com.tencent.wxcloudrun.service.MeetingRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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

  private static final Logger logger = LoggerFactory.getLogger(MeetingRoomServiceImpl.class);
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("^\\d{6}$");
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final String COMPANY_A = "万事网联";
  private static final String COMPANY_B = "万事达卡";
  private static final String STATUS_PENDING = "pending";
  private static final String STATUS_CANCELLED = "cancelled";
  private static final String NOTIFY_STATUS_PENDING = "pending";
  private static final String DISPLAY_OCCUPIED = "已占用";
  private static final LocalTime BOOKING_OPEN_TIME = LocalTime.of(9, 0);
  private static final LocalTime BOOKING_CLOSE_TIME = LocalTime.of(18, 0);

  private final MeetingRoomMapper meetingRoomMapper;
  private final ObjectMapper objectMapper;
  private Clock clock = Clock.systemDefaultZone();

  @Value("${wx.app-id:}")
  private String wxAppId;

  @Value("${wx.app-secret:}")
  private String wxAppSecret;

  @Value("${wx.dev-open-id:local_dev_openid}")
  private String wxDevOpenId;

  @Value("${notification.zone-id:Asia/Shanghai}")
  private String zoneId;

  public MeetingRoomServiceImpl(@Autowired MeetingRoomMapper meetingRoomMapper, @Autowired ObjectMapper objectMapper) {
    this.meetingRoomMapper = meetingRoomMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public Map<String, Object> checkUser(CheckUserRequest request) {
    if (request == null) {
      logger.warn("auth.check-user rejected reason=missing_request");
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "缺少登录参数");
    }
    logger.info("auth.check-user start hasOpenId={} hasCode={}", !isBlank(request.getOpenId()), !isBlank(request.getCode()));
    String openId = resolveOpenId(request);
    User user = meetingRoomMapper.findUserByOpenId(openId);
    logger.info("auth.check-user done openId={} registered={} userId={}", maskOpenId(openId), user != null, user == null ? "-" : user.getId());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("registered", user != null);
    data.put("openId", openId);
    data.put("user", user == null ? null : userToMap(user, true));
    return data;
  }

  @Override
  public Map<String, Object> verifyInvite(InviteVerifyRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getInviteCode())) {
      logger.warn("invite.verify rejected reason=missing_param openId={}", request == null ? "-" : maskOpenId(request.getOpenId()));
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "openId 和 inviteCode 不能为空");
    }
    logger.info("invite.verify start openId={} inviteCode={}", maskOpenId(request.getOpenId()), maskCode(request.getInviteCode()));
    if (!INVITE_CODE_PATTERN.matcher(request.getInviteCode()).matches()) {
      logger.warn("invite.verify rejected reason=invalid_format openId={} inviteCode={}", maskOpenId(request.getOpenId()), maskCode(request.getInviteCode()));
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "邀请码必须为 6 位数字");
    }

    InviteCode inviteCode = meetingRoomMapper.findInviteByCode(request.getInviteCode());
    if (!isInviteUsable(inviteCode)) {
      logger.warn("invite.verify rejected reason=invalid_or_expired openId={} inviteCode={}", maskOpenId(request.getOpenId()), maskCode(request.getInviteCode()));
      throw new ApiException(ApiErrorCode.INVALID_INVITE_CODE, "邀请码错误或已失效");
    }
    logger.info("invite.verify done openId={} inviteId={} companyScopeSize={}", maskOpenId(request.getOpenId()), inviteCode.getId(), parseStringList(inviteCode.getCompanyScope()).size());

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
    String firstName = request.getFirstName().trim();
    String lastName = request.getLastName().trim();
    String displayName = firstName + " " + lastName;
    logger.info("users.register start openId={} company={} hasInviteId={}", maskOpenId(request.getOpenId()), request.getCompany(), !isBlank(request.getInviteId()));
    InviteCode inviteCode = null;
    if (!isBlank(request.getInviteId())) {
      inviteCode = meetingRoomMapper.findInviteById(request.getInviteId());
      if (!isInviteUsable(inviteCode)) {
        logger.warn("users.register rejected reason=invalid_invite openId={} inviteId={}", maskOpenId(request.getOpenId()), request.getInviteId());
        throw new ApiException(ApiErrorCode.INVALID_INVITE_CODE, "邀请码错误或已失效");
      }
      List<String> companyScope = parseStringList(inviteCode.getCompanyScope());
      if (!companyScope.isEmpty() && !companyScope.contains(request.getCompany())) {
        logger.warn("users.register rejected reason=company_out_of_scope openId={} inviteId={} company={}", maskOpenId(request.getOpenId()), request.getInviteId(), request.getCompany());
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "公司不在邀请码允许范围内");
      }
    }

    User user = meetingRoomMapper.findUserByOpenId(request.getOpenId());
    if (user == null) {
      user = new User();
      user.setId(newId("u"));
      user.setOpenId(request.getOpenId());
      user.setFirstName(firstName);
      user.setLastName(lastName);
      user.setName(displayName);
      user.setCompany(request.getCompany());
      user.setEmail(request.getEmail().trim());
      meetingRoomMapper.insertUser(user);
      logger.info("users.register inserted openId={} userId={} company={}", maskOpenId(user.getOpenId()), user.getId(), user.getCompany());
    } else {
      user.setFirstName(firstName);
      user.setLastName(lastName);
      user.setName(displayName);
      user.setCompany(request.getCompany());
      user.setEmail(request.getEmail().trim());
      meetingRoomMapper.updateUser(user);
      logger.info("users.register updated openId={} userId={} company={}", maskOpenId(user.getOpenId()), user.getId(), user.getCompany());
    }

    if (inviteCode != null) {
      markInviteUsed(inviteCode, request.getOpenId());
    }

    Map<String, Object> data = new LinkedHashMap<>();
    User savedUser = meetingRoomMapper.findUserByOpenId(request.getOpenId());
    logger.info("users.register done openId={} userId={}", maskOpenId(request.getOpenId()), savedUser == null ? "-" : savedUser.getId());
    data.put("user", userToMap(savedUser, true));
    return data;
  }

  @Override
  public Map<String, Object> getProfile(String openId) {
    logger.info("users.profile start openId={}", maskOpenId(openId));
    User user = requireUser(openId);
    logger.info("users.profile done openId={} userId={}", maskOpenId(openId), user.getId());
    return userToMap(user, false);
  }

  @Override
  public Map<String, Object> getHomeSummary(String openId) {
    logger.info("home.summary start openId={}", maskOpenId(openId));
    User user = requireUser(openId);
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("name", user.getName());
    profile.put("company", user.getCompany());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profile", profile);
    int pendingBookingCount = meetingRoomMapper.countPendingBookingsByOrganizer(openId);
    data.put("pendingBookingCount", pendingBookingCount);
    logger.info("home.summary done openId={} userId={} pendingBookingCount={}", maskOpenId(openId), user.getId(), pendingBookingCount);
    return data;
  }

  @Override
  public Map<String, Object> listRooms(String date, String startTime, String endTime, Boolean onlyAvailable) {
    logger.info("rooms.list start date={} startTime={} endTime={} onlyAvailable={}", date, startTime, endTime, onlyAvailable);
    boolean hasTimeQuery = !isBlank(date) || !isBlank(startTime) || !isBlank(endTime);
    LocalDate queryDate = null;
    LocalTime queryStart = null;
    LocalTime queryEnd = null;
    if (hasTimeQuery) {
      if (isBlank(date) || isBlank(startTime) || isBlank(endTime)) {
        logger.warn("rooms.list rejected reason=incomplete_time_query date={} startTime={} endTime={}", date, startTime, endTime);
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "date/startTime/endTime 必须同时提供");
      }
      queryDate = parseDate(date);
      queryStart = parseTime(startTime);
      queryEnd = parseTime(endTime);
      validateTimeRange(queryStart, queryEnd);
    }

    ZoneId currentZone = currentZoneId();
    LocalDate today = LocalDate.now(currentZone);
    LocalTime now = LocalTime.now(currentZone);
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
    logger.info("rooms.list done hasTimeQuery={} returnedRooms={}", hasTimeQuery, roomMaps.size());
    return data;
  }

  @Override
  public Map<String, Object> getRoomStatus(String date) {
    logger.info("rooms.status start date={}", date);
    ZoneId currentZone = currentZoneId();
    Clock currentClock = currentClock(currentZone);
    LocalTime currentTime = LocalTime.now(currentClock);
    String statusDate = isBlank(date) ? LocalDate.now(currentClock).format(DATE_FORMATTER) : parseDate(date).format(DATE_FORMATTER);
    String now = currentTime.format(TIME_FORMATTER);
    boolean openForBooking = isOpenForBooking(currentTime);
    Map<String, Booking> activeBookings = activeBookingMap(statusDate, now);

    int availableCount = 0;
    int busyCount = 0;
    int unavailableCount = 0;
    List<Map<String, Object>> rooms = new ArrayList<>();
    for (Room room : meetingRoomMapper.listEnabledRooms()) {
      Booking activeBooking = activeBookings.get(room.getId());
      boolean busy = activeBooking != null;
      boolean available = !busy && openForBooking;
      boolean unavailable = !busy && !openForBooking;
      if (available) {
        availableCount++;
      } else if (busy) {
        busyCount++;
      } else {
        unavailableCount++;
      }
      rooms.add(roomStatusMap(room, activeBooking, available, unavailable));
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("available", availableCount);
    summary.put("busy", busyCount);
    summary.put("unavailable", unavailableCount);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("rooms", rooms);
    logger.info("rooms.status done date={} available={} busy={} unavailable={} returnedRooms={}", statusDate, availableCount, busyCount, unavailableCount, rooms.size());
    return data;
  }

  @Override
  public Map<String, Object> getRoomCalendar(String roomId, String startDate, Integer days, String viewerOpenId) {
    logger.info("rooms.calendar start roomId={} startDate={} days={} viewerOpenId={}", roomId, startDate, days, maskOpenId(viewerOpenId));
    if (isBlank(roomId) || isBlank(startDate) || isBlank(viewerOpenId)) {
      logger.warn("rooms.calendar rejected reason=missing_param roomId={} startDate={} viewerOpenId={}", roomId, startDate, maskOpenId(viewerOpenId));
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "roomId/startDate/viewerOpenId 不能为空");
    }
    Room room = meetingRoomMapper.findRoomById(roomId);
    if (room == null) {
      logger.warn("rooms.calendar rejected reason=room_not_found roomId={} viewerOpenId={}", roomId, maskOpenId(viewerOpenId));
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    User viewer = requireUser(viewerOpenId);
    int dayCount = days == null ? 5 : days;
    if (dayCount < 1 || dayCount > 5) {
      logger.warn("rooms.calendar rejected reason=invalid_days roomId={} days={}", roomId, dayCount);
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "days 当前仅支持 1 到 5 天");
    }
    LocalDate firstDate = parseDate(startDate);
    LocalDate endDate = firstDate.plusDays(dayCount - 1L);
    List<Booking> bookings = meetingRoomMapper.listBookingsByRoomAndDateRange(roomId, firstDate.format(DATE_FORMATTER), endDate.format(DATE_FORMATTER));
    Map<String, List<Booking>> bookingsByDate = groupBookingsByDate(bookings);
    int hiddenTitleCount = countHiddenTitles(bookings, viewer.getCompany());

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
    roomData.put("roomCapacity", room.getRoomCapacity());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("room", roomData);
    data.put("days", daysData);
    logger.info("rooms.calendar done roomId={} viewerOpenId={} days={} bookingCount={} hiddenTitleCount={}", roomId, maskOpenId(viewerOpenId), dayCount, bookings.size(), hiddenTitleCount);
    return data;
  }

  @Override
  public Map<String, Object> searchUsers(String keyword, String viewerOpenId, Integer limit) {
    logger.info("users.search start viewerOpenId={} hasKeyword={} limit={}", maskOpenId(viewerOpenId), !isBlank(keyword), limit);
    User viewer = requireUser(viewerOpenId);
    if (isBlank(keyword)) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("users", new ArrayList<Map<String, Object>>());
      logger.info("users.search done viewerOpenId={} returnedUsers=0 reason=blank_keyword", maskOpenId(viewerOpenId));
      return data;
    }
    int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
    List<Map<String, Object>> users = new ArrayList<>();
    for (User user : meetingRoomMapper.searchUsers(keyword.trim(), viewer.getCompany(), safeLimit)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", user.getId());
      item.put("openId", user.getOpenId());
      item.put("firstName", user.getFirstName());
      item.put("lastName", user.getLastName());
      item.put("name", user.getName());
      item.put("company", user.getCompany());
      item.put("email", user.getEmail());
      users.add(item);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("users", users);
    logger.info("users.search done viewerOpenId={} returnedUsers={} limit={}", maskOpenId(viewerOpenId), users.size(), safeLimit);
    return data;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Map<String, Object> createBooking(CreateBookingRequest request) {
    validateCreateBookingRequest(request);
    logger.info("bookings.create start organizerOpenId={} roomId={} date={} startTime={} endTime={} attendeeCount={}",
        maskOpenId(request.getOrganizerOpenId()), request.getRoomId(), request.getDate(), request.getStartTime(), request.getEndTime(), request.getAttendees().size());
    User organizer = requireUser(request.getOrganizerOpenId());
    Room room = meetingRoomMapper.findRoomById(request.getRoomId());
    if (room == null) {
      logger.warn("bookings.create rejected reason=room_not_found organizerOpenId={} roomId={}", maskOpenId(request.getOrganizerOpenId()), request.getRoomId());
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    LocalTime start = parseTime(request.getStartTime());
    LocalTime end = parseTime(request.getEndTime());
    validateTimeRange(start, end);

    List<Booking> lockedBookings = meetingRoomMapper.lockBookingsByRoomAndDate(request.getRoomId(), request.getDate());
    logger.info("bookings.create conflict_check roomId={} date={} existingBookings={}", request.getRoomId(), request.getDate(), lockedBookings.size());
    Booking conflict = findConflict(lockedBookings, start, end, null);
    if (conflict != null) {
      logger.warn("bookings.create rejected reason=booking_conflict organizerOpenId={} roomId={} date={} startTime={} endTime={} conflictBookingId={}",
          maskOpenId(request.getOrganizerOpenId()), request.getRoomId(), request.getDate(), request.getStartTime(), request.getEndTime(), conflict.getId());
      throw bookingConflict(conflict.getId());
    }

    List<Map<String, Object>> attendees = normalizeAttendees(request.getAttendees(), organizer);
  validateRoomCapacity(room, attendees.size());
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
    logger.info("bookings.create inserted bookingId={} organizerOpenId={} organizerUserId={} roomId={} date={} startTime={} endTime={} attendeeCount={}",
      booking.getId(), maskOpenId(booking.getOrganizerOpenId()), booking.getOrganizerUserId(), booking.getRoomId(), booking.getDate(), booking.getStartTime(), booking.getEndTime(), attendees.size());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("booking", bookingToSummaryMap(meetingRoomMapper.findBookingById(booking.getId())));
    logger.info("bookings.create done bookingId={}", booking.getId());
    return data;
  }

  @Override
  public Map<String, Object> getMyBookings(String openId, String status, Boolean includeAttendee) {
    logger.info("bookings.my start openId={} status={} includeAttendee={}", maskOpenId(openId), status, includeAttendee);
    User currentUser = requireUser(openId);
    String queryStatus = STATUS_PENDING;
    boolean shouldIncludeAttendee = includeAttendee == null || Boolean.TRUE.equals(includeAttendee);
    List<Map<String, Object>> bookings = new ArrayList<>();
    Set<String> bookingIds = new HashSet<>();
    for (Booking booking : meetingRoomMapper.listMyBookings(openId, queryStatus)) {
      bookings.add(bookingToListMap(booking, "organizer", true));
      bookingIds.add(booking.getId());
    }
    if (shouldIncludeAttendee) {
      for (Booking booking : meetingRoomMapper.listAttendeeBookings(currentUser.getId(), queryStatus)) {
        if (bookingIds.contains(booking.getId()) || !isAttendee(booking, currentUser.getId())) {
          continue;
        }
        bookings.add(bookingToListMap(booking, "attendee", false));
        bookingIds.add(booking.getId());
      }
    }
    sortBookingMaps(bookings);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bookings", bookings);
    logger.info("bookings.my done openId={} status={} includeAttendee={} returnedBookings={}", maskOpenId(openId), queryStatus, includeAttendee, bookings.size());
    return data;
  }

  @Override
  @Transactional
  public Map<String, Object> cancelBooking(String bookingId, String openId) {
    logger.info("bookings.cancel start bookingId={} openId={}", bookingId, maskOpenId(openId));
    requireUser(openId);
    Booking booking = meetingRoomMapper.findBookingById(bookingId);
    if (booking == null) {
      logger.warn("bookings.cancel rejected reason=booking_not_found bookingId={} openId={}", bookingId, maskOpenId(openId));
      throw new ApiException(ApiErrorCode.BOOKING_NOT_FOUND, "预约记录不存在");
    }
    if (!openId.equals(booking.getOrganizerOpenId())) {
      logger.warn("bookings.cancel rejected reason=permission_denied bookingId={} openId={} organizerOpenId={}", bookingId, maskOpenId(openId), maskOpenId(booking.getOrganizerOpenId()));
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限取消该预约");
    }
    if (!STATUS_PENDING.equals(booking.getStatus())) {
      logger.warn("bookings.cancel rejected reason=invalid_status bookingId={} status={}", bookingId, booking.getStatus());
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前预约状态不允许取消");
    }
    meetingRoomMapper.cancelBooking(bookingId);
    logger.info("bookings.cancel done bookingId={} openId={}", bookingId, maskOpenId(openId));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bookingId", bookingId);
    data.put("status", STATUS_CANCELLED);
    return data;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Map<String, Object> rescheduleBooking(String bookingId, RescheduleBookingRequest request) {
    logger.info("bookings.reschedule start bookingId={} openId={} organizerOpenId={} roomId={} date={} startTime={} endTime={} attendeeCount={}",
        bookingId, request == null ? "-" : maskOpenId(request.getOpenId()), request == null ? "-" : maskOpenId(request.getOrganizerOpenId()),
        request == null ? null : request.getRoomId(), request == null ? null : request.getDate(), request == null ? null : request.getStartTime(),
        request == null ? null : request.getEndTime(), request == null || request.getAttendees() == null ? null : request.getAttendees().size());
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getOrganizerOpenId()) || isBlank(request.getRoomId()) || isBlank(request.getDate())
        || isBlank(request.getStartTime()) || isBlank(request.getEndTime()) || isBlank(request.getTitle())
        || request.getAttendees() == null || request.getAttendees().isEmpty()) {
      logger.warn("bookings.reschedule rejected reason=missing_param bookingId={}", bookingId);
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "改期参数不完整");
    }
    User actor = requireUser(request.getOpenId());
    User organizer = requireUser(request.getOrganizerOpenId());
    if (!actor.getOpenId().equals(organizer.getOpenId())) {
      logger.warn("bookings.reschedule rejected reason=actor_organizer_mismatch bookingId={} openId={} organizerOpenId={}", bookingId, maskOpenId(request.getOpenId()), maskOpenId(request.getOrganizerOpenId()));
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限改期该预约");
    }
    Booking booking = meetingRoomMapper.findBookingById(bookingId);
    if (booking == null) {
      logger.warn("bookings.reschedule rejected reason=booking_not_found bookingId={} openId={}", bookingId, maskOpenId(request.getOpenId()));
      throw new ApiException(ApiErrorCode.BOOKING_NOT_FOUND, "预约记录不存在");
    }
    if (!request.getOpenId().equals(booking.getOrganizerOpenId()) || !request.getOrganizerOpenId().equals(booking.getOrganizerOpenId())) {
      logger.warn("bookings.reschedule rejected reason=permission_denied bookingId={} openId={} organizerOpenId={} existingOrganizerOpenId={}",
          bookingId, maskOpenId(request.getOpenId()), maskOpenId(request.getOrganizerOpenId()), maskOpenId(booking.getOrganizerOpenId()));
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限改期该预约");
    }
    if (!STATUS_PENDING.equals(booking.getStatus())) {
      logger.warn("bookings.reschedule rejected reason=invalid_status bookingId={} status={}", bookingId, booking.getStatus());
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "当前预约状态不允许改期");
    }
    Room room = meetingRoomMapper.findRoomById(request.getRoomId());
    if (room == null) {
      logger.warn("bookings.reschedule rejected reason=room_not_found bookingId={} roomId={}", bookingId, request.getRoomId());
      throw new ApiException(ApiErrorCode.ROOM_NOT_FOUND, "会议室不存在");
    }
    parseDate(request.getDate());
    LocalTime start = parseTime(request.getStartTime());
    LocalTime end = parseTime(request.getEndTime());
    validateTimeRange(start, end);

    List<Booking> lockedBookings = meetingRoomMapper.lockBookingsByRoomAndDate(request.getRoomId(), request.getDate());
    logger.info("bookings.reschedule conflict_check bookingId={} roomId={} date={} existingBookings={}", bookingId, request.getRoomId(), request.getDate(), lockedBookings.size());
    Booking conflict = findConflict(lockedBookings, start, end, bookingId);
    if (conflict != null) {
      logger.warn("bookings.reschedule rejected reason=booking_conflict bookingId={} conflictBookingId={} roomId={} date={} startTime={} endTime={}",
          bookingId, conflict.getId(), request.getRoomId(), request.getDate(), request.getStartTime(), request.getEndTime());
      throw bookingConflict(conflict.getId());
    }

    List<Map<String, Object>> attendees = normalizeAttendees(request.getAttendees(), organizer);
    validateRoomCapacity(room, attendees.size());

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
    meetingRoomMapper.updateBookingSchedule(booking);
    logger.info("bookings.reschedule done bookingId={} roomId={} date={} startTime={} endTime={} attendeeCount={}", bookingId, request.getRoomId(), request.getDate(), request.getStartTime(), request.getEndTime(), attendees.size());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("booking", bookingToSummaryMap(meetingRoomMapper.findBookingById(bookingId)));
    return data;
  }

  @Override
  @Transactional
  public Map<String, Object> subscribeBooking(String bookingId, SubscribeBookingRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getTemplateId())) {
      logger.warn("bookings.subscribe rejected reason=missing_param bookingId={} openId={}", bookingId, request == null ? "-" : maskOpenId(request.getOpenId()));
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "订阅参数不完整");
    }
    logger.info("bookings.subscribe start bookingId={} openId={} templateId={}", bookingId, maskOpenId(request.getOpenId()), maskTemplateId(request.getTemplateId()));
    requireUser(request.getOpenId());
    Booking booking = meetingRoomMapper.findBookingById(bookingId);
    if (booking == null) {
      logger.warn("bookings.subscribe rejected reason=booking_not_found bookingId={} openId={}", bookingId, maskOpenId(request.getOpenId()));
      throw new ApiException(ApiErrorCode.BOOKING_NOT_FOUND, "预约记录不存在");
    }
    if (!canReceiveBookingNotification(booking, request.getOpenId())) {
      logger.warn("bookings.subscribe rejected reason=permission_denied bookingId={} openId={} organizerOpenId={}", bookingId, maskOpenId(request.getOpenId()), maskOpenId(booking.getOrganizerOpenId()));
      throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "无权限订阅该预约通知");
    }

    BookingSubscription subscription = new BookingSubscription();
    subscription.setId(newId("sub"));
    subscription.setBookingId(bookingId);
    subscription.setOpenId(request.getOpenId().trim());
    subscription.setTemplateId(request.getTemplateId().trim());
    subscription.setSubscribed(true);
    subscription.setNotifyStatus(NOTIFY_STATUS_PENDING);
    subscription.setRetryCount(0);
    meetingRoomMapper.upsertBookingSubscription(subscription);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("bookingId", bookingId);
    data.put("templateId", request.getTemplateId().trim());
    data.put("subscribed", true);
    logger.info("bookings.subscribe done bookingId={} openId={} templateId={}", bookingId, maskOpenId(request.getOpenId()), maskTemplateId(request.getTemplateId()));
    return data;
  }

  private String resolveOpenId(CheckUserRequest request) {
    if (isBlank(wxAppId) || isBlank(wxAppSecret)) {
      logger.info("auth.resolve-openid source=dev_open_id wxConfigPresent=false devOpenId={}", maskOpenId(wxDevOpenId));
      return wxDevOpenId.trim();
    }
    if (isBlank(request.getCode())) {
      logger.warn("auth.resolve-openid rejected reason=missing_code wxConfigPresent=true clientOpenId={}", maskOpenId(request.getOpenId()));
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "code 不能为空");
    }
    if (!isBlank(request.getOpenId())) {
      logger.warn("auth.resolve-openid ignored clientOpenId={} because wxConfigPresent=true", maskOpenId(request.getOpenId()));
    }
    try {
      logger.info("auth.resolve-openid source=wechat_api wxConfigPresent=true");
      String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + wxAppId
          + "&secret=" + wxAppSecret
          + "&js_code=" + request.getCode().trim()
          + "&grant_type=authorization_code";
      String body = new RestTemplate().getForObject(url, String.class);
      JsonNode json = objectMapper.readTree(body);
      if (json.hasNonNull("openid")) {
        logger.info("auth.resolve-openid done source=wechat_api openId={}", maskOpenId(json.get("openid").asText()));
        return json.get("openid").asText();
      }
      logger.warn("auth.resolve-openid rejected source=wechat_api response={}", json.toString());
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, json.has("errmsg") ? json.get("errmsg").asText() : "微信登录失败");
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      logger.warn("auth.resolve-openid failed source=wechat_api message={}", ex.getMessage());
      throw new ApiException(ApiErrorCode.UNAUTHORIZED, "微信登录失败");
    }
  }

  private void validateRegisterRequest(RegisterRequest request) {
    if (request == null || isBlank(request.getOpenId()) || isBlank(request.getFirstName()) || isBlank(request.getLastName()) || isBlank(request.getCompany())
        || isBlank(request.getEmail())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "注册信息不完整");
    }
    String firstName = request.getFirstName().trim();
    String lastName = request.getLastName().trim();
    String name = firstName + " " + lastName;
    if (firstName.length() > 30 || lastName.length() > 30) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "名和姓长度需为 1-30 个字符");
    }
    if (name.length() < 2 || name.length() > 30) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "姓名长度需为 2-30 个字符");
    }
    if (!COMPANY_A.equals(request.getCompany()) && !COMPANY_B.equals(request.getCompany())) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "公司不合法");
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
    return roomStatusMap(room, activeBooking, available, false);
  }

  private Map<String, Object> roomStatusMap(Room room, Booking activeBooking, boolean available, boolean unavailable) {
    boolean busy = activeBooking != null;
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", room.getId());
    item.put("name", room.getName());
    item.put("roomCapacity", room.getRoomCapacity());
    item.put("status", busy ? "busy" : (unavailable ? "unavailable" : "available"));
    item.put("statusText", busy ? "使用中" : (unavailable ? "非开放时间" : "可预订"));
    item.put("nextInfo", busy ? "使用中至 " + activeBooking.getEndTime() : (unavailable ? "开放时间 09:00-18:00" : "现在可立即预定"));
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

  private void validateRoomCapacity(Room room, int attendeeCount) {
    Integer roomCapacity = room.getRoomCapacity();
    if (roomCapacity != null && roomCapacity > 0 && attendeeCount > roomCapacity) {
      throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "参会人数不能超过会议室容量");
    }
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
      if (!organizer.getCompany().equals(user.getCompany())) {
        throw new ApiException(ApiErrorCode.VALIDATION_ERROR, "参会人员必须与预约人属于同一家公司");
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

  private boolean canReceiveBookingNotification(Booking booking, String openId) {
    if (openId.equals(booking.getOrganizerOpenId())) {
      return true;
    }
    User user = meetingRoomMapper.findUserByOpenId(openId);
    if (user == null || isBlank(booking.getAttendees())) {
      return false;
    }
    try {
      List<Map<String, Object>> attendees = objectMapper.readValue(booking.getAttendees(), new TypeReference<List<Map<String, Object>>>() {});
      for (Map<String, Object> attendee : attendees) {
        Object userId = attendee.get("userId");
        if (userId != null && user.getId().equals(String.valueOf(userId))) {
          return true;
        }
      }
    } catch (Exception ex) {
      logger.warn("bookings.subscribe attendee_parse_failed bookingId={} message={}", booking.getId(), ex.getMessage());
    }
    return false;
  }

  private boolean isAttendee(Booking booking, String userId) {
    if (isBlank(booking.getAttendees())) {
      return false;
    }
    try {
      List<Map<String, Object>> attendees = objectMapper.readValue(booking.getAttendees(), new TypeReference<List<Map<String, Object>>>() {});
      for (Map<String, Object> attendee : attendees) {
        Object attendeeUserId = attendee.get("userId");
        if (attendeeUserId != null && userId.equals(String.valueOf(attendeeUserId))) {
          return true;
        }
      }
    } catch (Exception ex) {
      logger.warn("bookings.my attendee_parse_failed bookingId={} message={}", booking.getId(), ex.getMessage());
    }
    return false;
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
      item.put("organizerName", booking.getOrganizerName());
      item.put("organizerDisplayName", booking.getOrganizerName());
      item.put("organizerCompany", booking.getOrganizerCompany());
      result.add(item);
    }
    return result;
  }

  private int countHiddenTitles(List<Booking> bookings, String viewerCompany) {
    int hiddenTitleCount = 0;
    for (Booking booking : bookings) {
      if (!viewerCompany.equals(booking.getOrganizerCompany())) {
        hiddenTitleCount++;
      }
    }
    return hiddenTitleCount;
  }

  private Map<String, Object> userToMap(User user, boolean includeOpenId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", user.getId());
    if (includeOpenId) {
      data.put("openId", user.getOpenId());
    }
    data.put("firstName", user.getFirstName());
    data.put("lastName", user.getLastName());
    data.put("name", user.getName());
    data.put("company", user.getCompany());
    data.put("email", user.getEmail());
    if (user.getCreatedAt() != null) {
      data.put("createdAt", user.getCreatedAt().toString());
    }
    return data;
  }

  private Map<String, Object> bookingToSummaryMap(Booking booking) {
    Map<String, Object> data = bookingToListMap(booking);
    data.put("organizerName", booking.getOrganizerName());
    data.put("organizerCompany", booking.getOrganizerCompany());
    return data;
  }

  private Map<String, Object> bookingToListMap(Booking booking) {
    return bookingToListMap(booking, null, null);
  }

  private Map<String, Object> bookingToListMap(Booking booking, String userRole, Boolean canManage) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", booking.getId());
    data.put("title", booking.getTitle());
    data.put("roomId", booking.getRoomId());
    data.put("roomName", booking.getRoomName());
    data.put("date", booking.getDate());
    data.put("startTime", booking.getStartTime());
    data.put("endTime", booking.getEndTime());
    data.put("status", booking.getStatus());
    data.put("attendees", parseAttendeeList(booking));
    if (userRole != null) {
      data.put("userRole", userRole);
    }
    if (canManage != null) {
      data.put("canManage", canManage);
    }
    return data;
  }

  private List<Map<String, Object>> parseAttendeeList(Booking booking) {
    if (booking == null || isBlank(booking.getAttendees())) {
      return new ArrayList<>();
    }
    try {
      return objectMapper.readValue(booking.getAttendees(), new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception ex) {
      logger.warn("bookings.attendees parse_failed bookingId={} message={}", booking.getId(), ex.getMessage());
      return new ArrayList<>();
    }
  }

  private void sortBookingMaps(List<Map<String, Object>> bookings) {
    bookings.sort((left, right) -> {
      String leftValue = String.valueOf(left.get("date")) + " " + String.valueOf(left.get("startTime"));
      String rightValue = String.valueOf(right.get("date")) + " " + String.valueOf(right.get("startTime"));
      return leftValue.compareTo(rightValue);
    });
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

  private ZoneId currentZoneId() {
    return ZoneId.of(isBlank(zoneId) ? "Asia/Shanghai" : zoneId);
  }

  private Clock currentClock(ZoneId zoneId) {
    return clock.withZone(zoneId);
  }

  private boolean isOpenForBooking(LocalTime time) {
    return !time.isBefore(BOOKING_OPEN_TIME) && time.isBefore(BOOKING_CLOSE_TIME);
  }

  private String maskOpenId(String openId) {
    if (isBlank(openId)) {
      return "-";
    }
    String value = openId.trim();
    if (value.length() <= 8) {
      return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
    return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
  }

  private String maskCode(String code) {
    if (isBlank(code)) {
      return "-";
    }
    String value = code.trim();
    if (value.length() <= 2) {
      return "**";
    }
    return value.substring(0, 1) + "****" + value.substring(value.length() - 1);
  }

  private String maskTemplateId(String templateId) {
    if (isBlank(templateId)) {
      return "-";
    }
    String value = templateId.trim();
    if (value.length() <= 12) {
      return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
    return value.substring(0, 6) + "***" + value.substring(value.length() - 6);
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
