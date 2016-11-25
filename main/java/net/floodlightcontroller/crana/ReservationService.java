package net.floodlightcontroller.crana;

import java.util.List;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.crana.SrcDstIP;

public interface ReservationService extends IFloodlightService {
	
	public List<SrcDstIP> getReservation();
}
