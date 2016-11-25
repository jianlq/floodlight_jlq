package net.floodlightcontroller.crana.stackelberg;

import java.io.InputStreamReader;
import java.util.Scanner;

public class Stackelberg {
	public static int callStackelberg() {
        String path = "E:\\OffLine\\Stackelberg\\Release\\Stackelberg.exe";
        System.out.println("#*#*#  Stackelberg is at your service");
        ProcessBuilder pb = new ProcessBuilder(path);
        pb.redirectErrorStream(true);
        int exitid = 1;
        try {
            Process process = pb.start();
            Scanner input = new Scanner(new InputStreamReader(process.getInputStream()));
            while(input.hasNext()){
            	System.out.println(input.nextLine());
            }
            exitid = process.waitFor(); //正常结束，返回0
            input.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
  
        return exitid;
    }
}
