package net.floodlightcontroller.crana;

import java.io.*;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LDUpdate;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.sflowcollector.ISflowListener;
import net.floodlightcontroller.sflowcollector.InterfaceStatistics;
import net.floodlightcontroller.topology.ITopologyListener;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.MatchUtils;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.sflowcollector.ISflowCollectionService;
import net.floodlightcontroller.crana.Edge;
import net.floodlightcontroller.crana.Demand;

import net.floodlightcontroller.crana.selfishrouting.*;
import net.floodlightcontroller.crana.trafficengineering.*;
import net.floodlightcontroller.crana.stackelberg.Stackelberg;

public class Coordinator implements IFloodlightModule, ReservationService,ITopologyListener,ISflowListener {
	private static final Logger log = LoggerFactory.getLogger(Coordinator.class);
	private static boolean isEnabled = false;
	private static final String ENABLED_STR = "enable";

	protected static String ROUTING_CHOICE = "TE"; // TE SR Stackelberg

	protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // TODO: find sweet spot
	protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
	public static final int INITIAL_DEALY = 30; // second
	public static final int PERIOD = 400; //second big because we only want compute once
	
	public static final int FLOW_DURATION = 2; //minutes : ditg duration time  
	public static final int Count = 20; // Numbers of computer utilization 
	public static int curCount = 0; 
	public static double SumUtil = 0.0;
	
	public static Random rand;

	public static int FLOWMOD_DEFAULT_IDLE_TIMEOUT = 300; // in seconds
	public static int FLOWMOD_DEFAULT_HARD_TIMEOUT = 310; // 0 means infinite
	public static int FLOWMOD_DEFAULT_PRIORITY = 233; 
	// 0 is the default table-miss flow in OF1.3+, so we need to use 1

	public static final int Coordinator_APP_ID = 3;
	static {
		AppCookie.registerApp(Coordinator_APP_ID, "Coordinator");
	}
	public static final U64 appCookie = AppCookie.makeCookie(Coordinator_APP_ID, 0);

	private static ITopologyService topologyService;
	private static ISflowCollectionService sflowCollectionService;
	private static IOFSwitchService switchService;
	private OFMessageDamper messageDamper;


	private int numDpid;
	private int numEdge;
	private static Set<Link> allLinks;
	private static Map<NodePortTuple, InterfaceStatistics> statisticsMap;
	private static List<Edge> incL;
	static ArrayList<Demand> req;
	public ArrayList<Integer> appver;// Overlay vertex
	private List<SrcDstIP> reservation;

	// after computing route
	public void PushAllRoute() {
		Map<Integer, List<Integer>> paths = readPath("inputFile//path.txt");
		for (Demand dm : req) {
			DatapathId srcDpid = DatapathId.of(dm.getSrc() + 1);
			OFPort sPort = dm.getSp();
			DatapathId dstDpid = DatapathId.of(dm.getDst() + 1);
			OFPort dPort = dm.getDp();

			List<Integer> path = paths.get(dm.getId());

			Route route = getRoute(path, srcDpid, sPort, dstDpid, dPort);

			pushBiRoute(route, dm.getSrc(), dm.getDst(), dm.getTcpSrcPort(), dm.getTcpDstPort());
		}
	}

	protected Match createMatch(int src, int dst, int tsp, int tdp) {
		DatapathId srcDpid = DatapathId.of(src + 1);
		// DatapathId dstDpid = DatapathId.of(dst + 1);

		IOFSwitch srcMac = switchService.getSwitch(srcDpid);
		// IOFSwitch dstMac = switchService.getSwitch(dstDpid);

		Match.Builder mb = srcMac.getOFFactory().buildMatch();

		IPv4Address sip = IPv4Address.of(167772160 + src + 1);
		IPv4Address dip = IPv4Address.of(167772160 + dst + 1);

		TransportPort udpSrcPort = TransportPort.of(tsp);
		TransportPort udpDstPort = TransportPort.of(tdp);

		boolean MATCH_UDP_PORT = false;
		if (MATCH_UDP_PORT) {
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
			.setExact(MatchField.IPV4_SRC, sip)
			.setExact(MatchField.IPV4_DST, dip)
			.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
			.setExact(MatchField.UDP_SRC, udpSrcPort)
			.setExact(MatchField.UDP_DST, udpDstPort);
		} else {
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
			.setExact(MatchField.IPV4_SRC, sip)
			.setExact(MatchField.IPV4_DST, dip);
		}

		return mb.build();
	}

	public void pushBiRoute(Route route, int src, int dst, int tsp, int tdp) {
		List<NodePortTuple> switchPortList = route.getPath();
		List<NodePortTuple> reSwitchPortList = new ArrayList<>();

		for (int i = switchPortList.size() - 1; i >= 0; i--) {
			reSwitchPortList.add(switchPortList.get(i));
		}

		Match match = createMatch(src, dst, tsp, tdp);
		pushRoute(switchPortList, match);

		Match reMatch = createMatch(dst, src, tdp, tsp);
		pushRoute(reSwitchPortList, reMatch);

	}

	public void pushRoute(List<NodePortTuple> switchPortList, Match match) {

		for (int indx = switchPortList.size() - 1; indx > 0; indx -= 2) {
			// indx and indx-1 will always have the same switch DPID.
			DatapathId switchDPID = switchPortList.get(indx).getNodeId();
			IOFSwitch sw = switchService.getSwitch(switchDPID);

			if (sw == null) {
				System.out.println("sw is null");
				return;
			}

			// need to build flow mod based on what type it is. Cannot set command later
			OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd();

			OFActionOutput.Builder aob = sw.getOFFactory().actions().buildOutput();
			List<OFAction> actions = new ArrayList<OFAction>();

			Match.Builder mb = MatchUtils.convertToVersion(match, sw.getOFFactory().getVersion());

			// set input and output ports on the switch
			OFPort outPort = switchPortList.get(indx).getPortId();
			OFPort inPort = switchPortList.get(indx - 1).getPortId();

			mb.setExact(MatchField.IN_PORT, inPort);

			aob.setPort(outPort);
			aob.setMaxLen(Integer.MAX_VALUE);
			actions.add(aob.build());

			// requestFlowRemovedNotification
			Set<OFFlowModFlags> flags = new HashSet<>();
			flags.add(OFFlowModFlags.SEND_FLOW_REM);
			fmb.setFlags(flags);

			// U64 cookie = U64.of(0);
			U64 cookie = AppCookie.makeCookie(Coordinator_APP_ID, 0);

			fmb.setMatch(mb.build())
			.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
			.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
			.setBufferId(OFBufferId.NO_BUFFER)
			.setCookie(cookie)
			.setOutPort(outPort)
			.setPriority(FLOWMOD_DEFAULT_PRIORITY);

			FlowModUtils.setActions(fmb, actions, sw);

			try {
				messageDamper.write(sw, fmb.build());

				/*
				log.info("Pushing Route flowmod routeIndx={} \n "+ 
				         "sw={}  srcIp = {} dstIp = {} inPort={} outPort={} ",
					 	 new Object[] { indx, 
					 	 sw, 
					 	 fmb.getMatch().get(MatchField.IPV4_SRC),
						 fmb.getMatch().get(MatchField.IPV4_DST), 
						 fmb.getMatch().get(MatchField.IN_PORT),
						 outPort } );
				*/
			} catch (IOException e) {
				log.error("Failure writing flow mod", e);
			}
		}
	}

	// convert a path to route
	public Route getRoute(List<Integer> path, DatapathId srcId, OFPort srcPort, DatapathId dstId, OFPort dstPort) {
		Route route = new Route(srcId, dstId);
		List<NodePortTuple> switchPorts = new ArrayList<>();
		switchPorts.add(new NodePortTuple(srcId, srcPort));
		if (path != null)
			for (Integer id : path) {
				if (incL != null && (incL.size() - 1) >= id) {
					switchPorts.add(incL.get(id).srcPort);
					switchPorts.add(incL.get(id).dstPort);
				}
			}
		switchPorts.add(new NodePortTuple(dstId, dstPort));

		route.setPath(switchPorts);

		return route;
	}

	// read path computed by our algorithm
	public Map<Integer, List<Integer>> readPath(String filename) {
		File file = new File(filename);
		if (!file.exists())
			System.out.println("path file doesn't exist.");

		Map<Integer, List<Integer>> paths = new HashMap<Integer, List<Integer>>();

		try {
			Scanner input = new Scanner(file);
			while (input.hasNext()) {
				String line = input.nextLine();
				String[] strAry = line.split(" ");
				Integer id = Integer.parseInt(strAry[0]);
				List<Integer> path = new ArrayList<>();
				for (int i = 1; i < strAry.length; i++)
					path.add(Integer.parseInt(strAry[i]));
				if (paths.get(id) == null)
					paths.put(id, path);
			}

			input.close();
		} catch (IOException e) {
			System.out.println(e);
		}
		return paths;
	}

	public void GenerateDemand() throws IOException {
		//int bgDemNum = rand.nextInt(numDpid * (numDpid - 1) / 4) + 1;
		//int orDemNum = rand.nextInt(bgDemNum) + 1;
		int bgDemNum = 15;
		int orDemNum = 5;
		req.clear();
		GenDemand(bgDemNum, orDemNum, "inputFile/req.txt", "inputFile/reqapp.txt", "inputFile/ditg.txt");
	}

	public void GenDemand(int bgDemNum, int orDemNum, String reqName, String appreqName, String ditgName)
			throws IOException {
		File reqFile = new File(reqName);
		PrintWriter outReq = new PrintWriter(reqFile);
		outReq.println(bgDemNum);

		File ditgFile = new File(ditgName);
		PrintWriter outDitg = new PrintWriter(ditgFile);

		File Refile = new File("inputFile/redirect");
		PrintWriter outRedirec = new PrintWriter(Refile);
		// background traffic
		/*
		int k = 0;
		for (int i = 0; i < numDpid; i++)
			for (int j = i + 1; j < numDpid; j++) {
				int flow = 409600;
				Demand dem = new Demand(k, i, j, flow);
				req.add(dem);
				outReq.println(dem.printDem());
				outDitg.print(genTraffic(k++, i, j, flow));
				outRedirec.println("ITGDec log" + j + " >> logsummary");
			}
			*/

			
		for (int i = 0; i < bgDemNum; i++) {
			int s = rand.nextInt(numDpid), t;
			do {
				t = rand.nextInt(numDpid);
			} while (s == t);
			//int flow = 40960+rand.nextInt(409600);
			int flow = 40960;
			Demand dem = new Demand(i, s, t, flow);
			req.add(dem);
			outReq.println(dem.printDem());
			outDitg.print(genTraffic(i, s, t, flow));
			outRedirec.println("ITGDec log" + t + " >> logsummary");
		}
		
		// Overlay traffic
		File appreqFile = new File(appreqName);
		PrintWriter outReqApp = new PrintWriter(appreqFile);
		outReqApp.println(orDemNum);
		
		int j = req.size();
		int n = appver.size();
		for (int i = 0; i < orDemNum; i++) {
			int s = rand.nextInt(n), t;
			do {
				t = rand.nextInt(n);
			} while (s == t);
			int flow = 40960;
			Demand dem = new Demand(i, appver.get(s), appver.get(t), flow * 1.0);
			req.add(dem);
			outReqApp.println(dem.printDem());
			outDitg.print(genTraffic(j++, appver.get(s), appver.get(t), flow));
			outRedirec.println("ITGDec log" + appver.get(t) + " >> logsummary");
		}
		outReqApp.close();
		
		outRedirec.close();
		outReq.close();
		outDitg.close();		
	}

	public String genTraffic(int i, int s, int t, int flow) {
		return "time x h" + (t + 1) + " xterm -title d" + i + "_h" + (t + 1) + "_recv -e ITGRecv -l log" + t + "\r\n" 
	            + "py time.sleep(0.2)\r\n" 
				+ "time x h" + (s + 1) + " xterm -title d" + i + "_h" + (s + 1) + "_send -e ITGSend -a 10.0.0." + (t + 1) + " -T UDP -C "+flow/4096 +" -c 512 -t "+FLOW_DURATION*60*1000+"\r\n" 
	            + "py time.sleep(0.2)\r\n";
	}

	public void genSflowSH(String sflowsh, int num) throws IOException {
		File sFile = new File(sflowsh);
		PrintWriter outSflow = new PrintWriter(sFile);
		for (int i = 1; i <= num; i++) {
			String ip = i >= 10 ? i + "" : "0" + i;
			outSflow.println("sudo ifconfig s" + i + " 10.0.0.1" + ip + " broadcast 10.255.255.255 netmask 255.0.0.0");
		}
		for (int i = 1; i <= num; i++) {
			outSflow.println("sudo ovs-vsctl -- --id=@sflow create sFlow agent=s" + i + " target=" + (char) (92)
					+ "\"127.0.0.1:6343" + (char) (92) + "\" header=128 sampling=64 polling=1 -- set bridge s" + i
					+ " sflow=@sflow");
		}
		outSflow.close();
	}

	// generate topology for Overlay or server selection
	public void ModifiedTopo(int n, int m) throws IOException {
		if (n <= 0 || m <= 0) {
			System.err.println("Error : in ModifiedTopo param n and m must be positive,please check param");
		}

		Set<Integer> ver = new HashSet<Integer>();
		while (ver.size() < n) {
			ver.add(rand.nextInt(this.numDpid) + 1);
		}
		
		appver.clear();
		for(Integer it : ver){
			appver.add(it);
		}

		File file = new File("inputFile\\topoOR.txt");
		PrintWriter ORout = new PrintWriter(file);
		ORout.println(n + " " + m);
		
		int bw = 0;
		for(int i = 0; i < m; i++){
			int s = rand.nextInt(n), t, cap = rand.nextInt(50), delay = rand.nextInt(10) + 1;		
			do{
				t = rand.nextInt(n);			
			}while (t == s);
			ORout.println(i + " " + appver.get(s) + " " + appver.get(t) + " " + cap + " " + bw + " " + delay );	
		}
		ORout.close();
	}

	public List<SrcDstIP> getReservation(){
		return reservation;
	}
	
	
	@Override // update one
	public void sflowCollected(Map<Integer, InterfaceStatistics> ifIndexIfStatMap) {
		statisticsMap = sflowCollectionService.getStatisticsMap();
		updateTopo();
		// System.out.println("*********** sflow update**********");
	}

	@Override // update two
	public void topologyChanged(List<LDUpdate> linkUpdates) {
	
		Map<DatapathId, Set<Link>> dpidLinks = topologyService.getAllLinks();
		Set<DatapathId> dpidSet = dpidLinks.keySet();
		allLinks.clear();
		if (dpidSet.size() != 0)
			numDpid = dpidSet.size();

		if (dpidSet != null) {
			for (DatapathId dpid : dpidSet) {
				Set<Link> linkSet = dpidLinks.get(dpid);
				if (linkSet == null)
					continue;
				allLinks.addAll(linkSet);
			}
		}
		numEdge = allLinks.size();
		updateTopo();
	}
	
	// called when topo changed or sflow get new statistics
	public void updateTopo() {
		File file = new File("inputFile//topo.txt");
		try {
			PrintWriter topoout = new PrintWriter(file);
			incL.clear();
			int id = 0;
			topoout.println(numDpid + " " + numEdge);

			if (allLinks != null)
				for (Link lk : allLinks)
					if (lk != null) {
						if (statisticsMap != null
								&& statisticsMap.containsKey(new NodePortTuple(lk.getSrc(), lk.getSrcPort()))
								&& statisticsMap.get(new NodePortTuple(lk.getSrc(), lk.getSrcPort())) != null) {
							double rate = statisticsMap.get(new NodePortTuple(lk.getSrc(), lk.getSrcPort())).getIfOutOctets().doubleValue();
							Edge edge = new Edge(id++, lk,rate);
							topoout.println(edge.printEdge());
							incL.add(edge);
						} else {
							Edge edge = new Edge(id++, lk, 0);
							topoout.println(edge.printEdge());
							incL.add(edge);
						}

					}
			topoout.close();
		} catch (IOException e) {
			System.out.println(e);
		}
	}
	

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = 
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ReservationService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(ReservationService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ITopologyService.class);
		l.add(ISflowCollectionService.class);
		l.add(IOFSwitchService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		topologyService = context.getServiceImpl(ITopologyService.class);
		sflowCollectionService = context.getServiceImpl(ISflowCollectionService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);

		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY, EnumSet.of(OFType.FLOW_MOD),
				OFMESSAGE_DAMPER_TIMEOUT);

		allLinks = new HashSet<Link>();
		incL = new ArrayList<Edge>();
		req = new ArrayList<Demand>();
		rand = new Random(System.currentTimeMillis());
		appver = new ArrayList<Integer>();
		reservation = new ArrayList<>();

		Map<String, String> config = context.getConfigParams(this);
		if (config.containsKey(ENABLED_STR)) {
			try {
				isEnabled = Boolean.parseBoolean(config.get(ENABLED_STR).trim());
			} catch (Exception e) {
				log.error("Could not parse '{}'. Using default of {}", ENABLED_STR, isEnabled);
			}
		}
		log.info("Coordinator {}", isEnabled ? "enabled" : "disabled");
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		if (isEnabled) {
			topologyService.addListener(this);
			sflowCollectionService.addSflowListener(this);
			log.info("\n*****Coordinator starts*****");
			Runnable test = new TestTask();
			ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
			service.scheduleAtFixedRate(test, INITIAL_DEALY, PERIOD, TimeUnit.SECONDS);

			File file = new File("inputFile//util.txt");
			  if(file.exists())
			    	file.delete();
			Runnable utiltest = new UtilTask();
			ScheduledExecutorService utilservice = Executors.newSingleThreadScheduledExecutor();
			utilservice.scheduleAtFixedRate(utiltest, 20, 5,TimeUnit.SECONDS);
		}
	}

	class TestTask implements Runnable {
		public void run() {
			int exitid = 1;
			try {
				updateTopo();
				ModifiedTopo((numDpid / 3 + 1), (numDpid / 3 + 1)* 3); /** *3 can change big when topology is big */
				genSflowSH("inputFile\\sflow.sh", numDpid);
				GenerateDemand();

				long t0 = System.currentTimeMillis();

				switch (ROUTING_CHOICE) {
				case "SR":
					exitid = SelfishRouting.callSR();
					break;
				case "TE":
					exitid = TrafficEngineering.callTE();
					break;
				case "Cheating":
					exitid = Stackelberg.callStackelberg();
					break;
				default:
					System.out.println("No this routing service. Stay tuned.");
				}

				long consuming = System.currentTimeMillis() - t0;

				if (exitid == 0)
					System.out.println("routing success, takes " + consuming + " ms");
				else {
					System.out.println("routing failed.");
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			System.out.println("read path and pushRoute");
			PushAllRoute();
			System.err.println("It's time to generate traffic.");
		}

	}

	class UtilTask implements Runnable {
		public void run() {
			 try {
				 double res = 0.0;
				for(Edge e :incL){
					double util = e.bw/e.capacity;
					res = Math.max(res, util);
				}
				//
				File f = new File("inputFile//util.txt");
				FileWriter outUtil = new FileWriter(f,true);
				outUtil.write(" " + res + "\r\n");
				System.err.println("bw is " + res*100000000 );
				System.err.println("link utilization is " + res );
				/*
				if(curCount < Count){
					SumUtil += res;
					curCount++;
				}
				else{
					System.out.println("----- link utilization is ------" + SumUtil/Count );
					System.out.println("----- link utilization is ------" );
				}*/
					
				outUtil.close();
			} catch (IOException e) {
				e.printStackTrace();
			}	
		}

	}

}