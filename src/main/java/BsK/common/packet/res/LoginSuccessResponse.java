package BsK.common.packet.res;

import BsK.common.packet.Packet;
import BsK.server.network.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class LoginSuccessResponse implements Packet {
  int id;
  Role role;
}
