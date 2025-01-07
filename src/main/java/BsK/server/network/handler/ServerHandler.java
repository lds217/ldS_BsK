package BsK.server.network.handler;

import BsK.common.packet.Packet;
import BsK.common.packet.PacketSerializer;
import BsK.common.packet.req.*;
import BsK.common.packet.res.*;
import BsK.common.packet.res.ErrorResponse.Error;
import BsK.server.network.manager.SessionManager;
import BsK.server.network.util.UserUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import lombok.extern.slf4j.Slf4j;

import static BsK.server.Server.statement;

@Slf4j
public class ServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
    log.debug("Received message: {}", frame.text());
    Packet packet = PacketSerializer.GSON.fromJson(frame.text(), Packet.class);


    if (packet instanceof LoginRequest loginRequest) {
      log.debug(
          "Received login request: {}, {}", loginRequest.getUsername(), loginRequest.getPassword());
      var user = SessionManager.getUserByChannel(ctx.channel().id().asLongText());
      user.authenticate(loginRequest.getUsername(), loginRequest.getPassword());

      if (user.isAuthenticated()) {
        UserUtil.sendPacket(user.getSessionId(), new LoginSuccessResponse(user.getUserId(), user.getRole()));
        log.info("Send response to client User {} authenticated, role {}, session {}", user.getUserId(), user.getRole(), user.getSessionId());
      } else {
        log.info("User {} failed to authenticate", user.getUserId());
        UserUtil.sendPacket(user.getSessionId(), new ErrorResponse(Error.INVALID_CREDENTIALS));
      }
    } else if (packet instanceof RegisterRequest registerRequest) {
      log.debug(
          "Received register request: {}, {}",
          registerRequest.getUsername(),
          registerRequest.getPassword());
      // Tạo user trong database hoặc check exist
      boolean isUserExist = false;
    } else {
      // Check if user is authenticated
      var user = SessionManager.getUserByChannel(ctx.channel().id().asLongText());
      if (!user.isAuthenticated()) {
        log.warn("Received packet from unauthenticated user: {}", packet);
        return;
      }

      if (packet instanceof GetCheckUpQueueRequest) {
        log.debug("Received GetCheckUpQueueRequest");
        try {
          ResultSet rs = statement.executeQuery(
                  "select a.checkup_id, a.checkup_date, c.customer_last_name, c.customer_first_name, " +
                          "d.doctor_first_name, d.doctor_last_name, a.symptoms, a.diagnosis, a.notes, a.status, a.customer_id " +
                          "from checkup as a " +
                          "join customer as c on a.customer_id = c.customer_id " +
                          "join Doctor D on a.doctor_id = D.doctor_id " +
                          "where a.status = 'PROCESSING'"
          );

          if (!rs.isBeforeFirst()) {
            System.out.println("No data found in the checkup table.");
          } else {
            ArrayList<String> resultList = new ArrayList<>();
            while (rs.next()) {
              String checkupId = rs.getString("checkup_id");
              String checkupDate = rs.getString("checkup_date");
              long checkupDateLong = Long.parseLong(checkupDate);
              Timestamp timestamp = new Timestamp(checkupDateLong);
              Date date = new Date(timestamp.getTime()); // Needed to recode
              SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
              checkupDate = sdf.format(date);
              String customerLastName = rs.getString("customer_last_name");
              String customerFirstName = rs.getString("customer_first_name");
              String doctorFirstName = rs.getString("doctor_first_name");
              String doctorLastName = rs.getString("doctor_last_name");
              String symptoms = rs.getString("symptoms");
              String diagnosis = rs.getString("diagnosis");
              String notes = rs.getString("notes");
              String status = rs.getString("status");
              String customerId = rs.getString("customer_id");


              String result = String.join("|", checkupId,
                      checkupDate, customerLastName, customerFirstName,
                      doctorLastName + " " + doctorFirstName, symptoms,
                      diagnosis, notes, status, customerId
              );

              resultList.add(result);

              // log.info(result);
            }

            String[] resultString = resultList.toArray(new String[0]);
            String[][] resultArray = new String[resultString.length][];
            for (int i = 0; i < resultString.length; i++) {
              resultArray[i] = resultString[i].split("\\|");
            }

            UserUtil.sendPacket(user.getSessionId(), new GetCheckUpQueueResponse(resultArray));

          }
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }


      // Get general doctor info
      if (packet instanceof GetDoctorGeneralInfo) {
        log.debug("Received GetDoctorGeneralInfo");
        try {
          ResultSet rs = statement.executeQuery(
                  "select concat(doctor_last_name, ' ', doctor_first_name) from Doctor"
          );

            if (!rs.isBeforeFirst()) {
                System.out.println("No data found in the doctor table.");
            } else {
              ArrayList<String> resultList = new ArrayList<>();
              while (rs.next()) {
                String doctorName = rs.getString(1);
                resultList.add(doctorName);
              }
              String[] resultString = resultList.toArray(new String[0]);
              UserUtil.sendPacket(user.getSessionId(), new GetDoctorGeneralInfoResponse(resultString));
              log.info("Send response to client GetDoctorGeneralInfo");
          }
        }
        catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }

      if (packet instanceof GetCustomerHistoryRequest getCustomerHistoryRequest) {
        log.debug("Received GetCustomerHistoryRequest");
        try {
            ResultSet rs = statement.executeQuery(
                    "select Checkup.checkup_date, Checkup.checkup_id, Checkup.symptoms, Checkup.diagnosis, Checkup.prescription_id, Checkup.notes\n" +
                            "from Customer\n" +
                            "join Checkup on Customer.customer_id = Checkup.customer_id\n" +
                            "where Checkup.status = \"DONE\" and Customer.customer_id = " +
                            getCustomerHistoryRequest.getCustomerId() +
                            " order by checkup_date"
            );

            if (!rs.isBeforeFirst()) {
                System.out.println("No data found in the checkup table.");
                UserUtil.sendPacket(user.getSessionId(), new GetCustomerHistoryResponse(new String[0][7]));
            } else {
                ArrayList<String> resultList = new ArrayList<>();
                while (rs.next()) {
                    String checkupDate = rs.getString("checkup_date");
                    long checkupDateLong = Long.parseLong(checkupDate);
                    Timestamp timestamp = new Timestamp(checkupDateLong);
                    Date date = new Date(timestamp.getTime()); // Needed to recode
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
                    checkupDate = sdf.format(date);
                    String checkupId = rs.getString("checkup_id");
                    String symptoms = rs.getString("symptoms");
                    String diagnosis = rs.getString("diagnosis");
                    String prescriptionId = rs.getString("prescription_id");
                    String notes = rs.getString("notes");
                    String result = String.join("|", checkupDate, checkupId, symptoms, diagnosis, prescriptionId, notes);
                    resultList.add(result);
                    // log.info(result);
                }

                String[] resultString = resultList.toArray(new String[0]);
                String[][] resultArray = new String[resultString.length][];
                for (int i = 0; i < resultString.length; i++) {
                    resultArray[i] = resultString[i].split("\\|");
                }

                UserUtil.sendPacket(user.getSessionId(), new GetCustomerHistoryResponse(resultArray));
            }

        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }

      if (packet instanceof GetMedInfoRequest getMedInfoRequest) {
        log.debug("Received GetMedInfoRequest");
        try {
          ResultSet rs = statement.executeQuery(
                  "select med_id, med_name, med_company, med_description, quantity, med_unit, med_selling_price\n" +
                          "    from Medicine"
          );

          if (!rs.isBeforeFirst()) {
            System.out.println("No data found in the medicine table.");
          } else {
            ArrayList<String> resultList = new ArrayList<>();
            while (rs.next()) {
                String medId = rs.getString("med_id");
                String medName = rs.getString("med_name");
                String medCompany = rs.getString("med_company");
                String medDescription = rs.getString("med_description");
                String quantity = rs.getString("quantity");
                String medUnit = rs.getString("med_unit");
                String medSellingPrice = rs.getString("med_selling_price");


                String result = String.join("|",medId, medName, medCompany, medDescription, quantity, medUnit,
                        medSellingPrice);
                resultList.add(result);
            }

            String[] resultString = resultList.toArray(new String[0]);
            String[][] resultArray = new String[resultString.length][];
            for (int i = 0; i < resultString.length; i++) {
              resultArray[i] = resultString[i].split("\\|");
            }

            UserUtil.sendPacket(user.getSessionId(), new GetMedInfoResponse(resultArray));
          }
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cause instanceof IOException) {
      SessionManager.onUserDisconnect(ctx.channel());
    } else {
      log.error("ERROR: ", cause);
    }
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent event) {
      if (event.state() == IdleState.READER_IDLE) {
        try {
          SessionManager.onUserDisconnect(ctx.channel());
          ctx.channel().close();
        } catch (Exception e) {
        }
      }
    } else if (evt instanceof HandshakeComplete) {
      int SessionId = SessionManager.onUserLogin(ctx.channel());
      log.info("Session {} logged in", SessionId);

      UserUtil.sendPacket(SessionId, new HandshakeCompleteResponse());
    } else {
      super.userEventTriggered(ctx, evt);
    }
  }
}
