package net.floodlightcontroller.crana.trafficengineering;

import java.io.InputStreamReader;
import java.util.Scanner;

public class TrafficEngineering {
	public static int callTE() {
		String path="E:/OffLine/TE/Release/TE.exe";
        System.out.println("#*#*#  Traffic Engineering is at your service");
        ProcessBuilder pb = new ProcessBuilder(path);
        pb.redirectErrorStream(true);
        int exitid = 1;
        try {
            Process process = pb.start();
            Scanner input = new Scanner(new InputStreamReader(process.getInputStream()));
            while(input.hasNext()){
            	System.out.println(input.nextLine());
            }
            exitid = process.waitFor();//正常结束，返回0
            input.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
  
        return exitid;
    }
}
