package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.Booking;
import com.tencent.wxcloudrun.model.BookingSubscription;
import com.tencent.wxcloudrun.model.InviteCode;
import com.tencent.wxcloudrun.model.NotificationCandidate;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MeetingRoomMapper {

  User findUserByOpenId(@Param("openId") String openId);

  User findUserById(@Param("id") String id);

  List<User> searchUsers(@Param("keyword") String keyword, @Param("company") String company, @Param("limit") Integer limit);

  void insertUser(User user);

  void updateUser(User user);

  InviteCode findInviteByCode(@Param("code") String code);

  InviteCode findInviteById(@Param("id") String id);

  void updateInviteUsedBy(@Param("id") String id, @Param("usedBy") String usedBy);

  Room findRoomById(@Param("id") String id);

  List<Room> listEnabledRooms();

  List<Booking> listBookingsByRoomAndDate(@Param("roomId") String roomId, @Param("date") String date);

  List<Booking> lockBookingsByRoomAndDate(@Param("roomId") String roomId, @Param("date") String date);

  List<Booking> listBookingsByRoomAndDateRange(@Param("roomId") String roomId,
                                                @Param("startDate") String startDate,
                                                @Param("endDate") String endDate);

  List<Booking> listActiveBookingsByDateTime(@Param("date") String date, @Param("time") String time);

  List<Booking> listMyBookings(@Param("openId") String openId, @Param("status") String status);

  List<Booking> listAttendeeBookings(@Param("userId") String userId, @Param("status") String status);

  Booking findBookingById(@Param("id") String id);

  void insertBooking(Booking booking);

  void cancelBooking(@Param("id") String id);

  void updateBookingSchedule(Booking booking);

  int countPendingBookingsByOrganizer(@Param("openId") String openId);

  void upsertBookingSubscription(BookingSubscription subscription);

  List<NotificationCandidate> listPendingNotificationCandidates(@Param("startDate") String startDate,
                                                                 @Param("startTime") String startTime,
                                                                 @Param("endDate") String endDate,
                                                                 @Param("endTime") String endTime,
                                                                 @Param("limit") Integer limit);

  int markSubscriptionSending(@Param("id") String id);

  void deleteBookingSubscription(@Param("id") String id);
}
