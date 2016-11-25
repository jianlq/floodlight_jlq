package net.floodlightcontroller.crana;

import org.projectfloodlight.openflow.types.IPv4Address;

public class SrcDstIP {
	private IPv4Address src;
	private IPv4Address dst;
	
	public SrcDstIP(int s,  int d){
		this.src = IPv4Address.of(167772160 + s + 1);
		this.dst = IPv4Address.of(167772160 + d + 1);
	}
	
	public SrcDstIP(IPv4Address src,  IPv4Address dst){
		this.src = src;
		this.dst = dst;
	}
	
	public IPv4Address getSrcIP(){
		return src;
	}
	
	public IPv4Address getDstIP(){
		return dst;
	}
	
	 @Override
     public boolean equals(Object obj) {
        if (this == obj)
        	return true;
	    if (obj == null)
	        return false;
	    if (getClass() != obj.getClass())
	        return false;
        SrcDstIP other = (SrcDstIP) obj;
        if (!src.equals(other.src))
	        return false;
        if (!dst.equals(other.dst))
        	return false;
	    return true; 
	 }
	 
	 @Override
	 public String toString() {
        return "[ srcIP= "
	    + src.toString()
	    +" -> dstPort= "
        + dst.toString()
        +" ]";
	 }
}
