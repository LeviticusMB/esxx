/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2015 Martin Blom <martin@blom.org>

     This program is free software: you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation, either version 3
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.esxx;

import java.io.*;
import java.net.URI;
import java.util.Properties;
import org.apache.commons.cli.*;
import org.esxx.request.*;

public class Main {
  private static void usage(Options opt, String error, int rc) {
    PrintWriter  err = new PrintWriter(System.err);
    HelpFormatter hf = new HelpFormatter();

    hf.printUsage(err, 80, "esxx.jar [OPTION...] [--script -- <script.js> SCRIPT ARGS...]");
    hf.printOptions(err, 80, opt, 2, 8);

    if (error != null) {
      err.println();
      hf.printWrapped(err, 80, "Invalid arguments: " + error + ".");
    }

    err.flush();
    System.exit(rc);
  }

  public static void main(String[] args) {
    // (Try to) Load embedded H2 database JDBC driver into memory
    try {
      Class.forName("org.h2.Driver");
    }
    catch (ClassNotFoundException ignored) {}

    Options opt = new Options();
    OptionGroup mode_opt = new OptionGroup();

    // (Remember: -u/--user, -p/--pidfile and -j/jvmargs are used by the wrapper script)
    mode_opt.addOption(new Option("b", "bind",    true, ("Listen for FastCGI requests on " +
							 "this <port>")));
    mode_opt.addOption(new Option("A", "ajp",     true, ("Listen for AJP13 requests on " +
							 "this <port>")));
    mode_opt.addOption(new Option("H", "http",    true, ("Listen for HTTP requests on " +
							 "this <port>")));
    mode_opt.addOption(new Option("s", "script", false, "Force script mode."));
    mode_opt.addOption(new Option("S", "shell",  false, "Enter ESXX shell mode."));
    mode_opt.addOption(new Option(null,"db-console", false, "Open H2's database console."));
    mode_opt.addOption(new Option(null,"version",    false, "Display version and exit"));

    opt.addOptionGroup(mode_opt);
    opt.addOption("n", "no-handler",      true,  "FCGI requests are direct, without extra handler");
    opt.addOption("r", "http-root",       true,  "Set AJP/FCGI/HTTP root directory (or file)");
//     opt.addOption("d", "enable-debugger", false, "Enable esxx.debug()");
//     opt.addOption("D", "start-debugger",  false, "Start debugger");
    opt.addOption(null,"pg-server",        true, "Expose embedded H2 in PostgreSQL mode on this <port>");
    opt.addOption(null,"h2-server",        true, "Expose embedded H2 in native TCP mode on this <port>");
    opt.addOption("?", "help",            false, "Show help");

    try {
      CommandLineParser parser = new GnuParser();
      CommandLine cmd = parser.parse(opt, args, false);

      int fastcgi_port  = -1;
      int     ajp_port  = -1;
      int    http_port  = -1;
      boolean run_shell = false;
      String[]  script  = null;

      if (cmd.hasOption('?')) {
	usage(opt, null, 0);
      }

      if (cmd.hasOption('b')) {
	fastcgi_port = Integer.parseInt(cmd.getOptionValue('b'));
      }
      else if (cmd.hasOption('A')) {
	ajp_port = Integer.parseInt(cmd.getOptionValue('A'));
      }
      else if (cmd.hasOption('H')) {
	http_port = Integer.parseInt(cmd.getOptionValue('H'));
      }
      else if (cmd.hasOption('s')) {
	script = cmd.getArgs();
      }
      else if (cmd.hasOption('S')) {
	run_shell = true;
      }
      else if (cmd.hasOption("db-console")) {
	org.h2.tools.Console.main(cmd.getArgs());
	return;
      }
      else if (cmd.hasOption("version")) {
	Properties p = new Properties();
	p.loadFromXML(Main.class.getResourceAsStream("/rsrc/esxx.properties"));
	System.err.println(p.getProperty("version"));
	return;
      }
      else {
	// Guess execution mode by looking at FCGI_PORT 
	String fcgi_port  = System.getenv("FCGI_PORT");

	if (fcgi_port != null) {
	  fastcgi_port = Integer.parseInt(fcgi_port);
	}
	else {
	  // Default mode is to execute a JS script
	  script = cmd.getArgs();
	}
      }

      if (script != null) {
	Properties p   = System.getProperties();
	String forever = Long.toString(3600 * 24 * 365 * 10 /* 10 years */);

	// "Never" unload Applications in script mode
	p.setProperty("esxx.cache.apps.max_age", forever);

	// Kill process immediately on Ctrl-C
	p.setProperty("esxx.app.clean_shutdown", "false");
      }

      ESXX esxx = ESXX.initInstance(System.getProperties(), null);

      if (script != null || run_shell) {
	// Lower default log level a bit
	esxx.getLogger().setLevel(java.util.logging.Level.INFO);
      }

      esxx.setNoHandlerMode(cmd.getOptionValue('n', "lighttpd.*"));

      // Install our ResponseCache implementation
//       java.net.ResponseCache.setDefault(new org.esxx.cache.DBResponseCache("/tmp/ESXX.WebCache", 
// 									   Integer.MAX_VALUE,
// 									   Long.MAX_VALUE, 
// 									   Long.MAX_VALUE));

      String h2_server = cmd.getOptionValue("h2-server");
      String pg_server = cmd.getOptionValue("pg-server");

      if (h2_server != null) {
        org.h2.tools.Server.createTcpServer(new String[] { "-tcpPort", h2_server, "-tcpAllowOthers", "-ifExists" }).start();
      }

      if (pg_server != null) {
        org.h2.tools.Server.createPgServer(new String[] { "-pgPort", pg_server, "-pgAllowOthers", "-ifExists" }).start();
      }

      // Default is to serve the current directory
      URI fs_root_uri = ESXX.createFSRootURI(cmd.getOptionValue('r', ""));

      if (fastcgi_port != -1 && !cmd.hasOption('r')) {
	// If not provided in FastCGI mode, use ${PATH_TRANSLATED}
	fs_root_uri = null;
      }

      if (fastcgi_port != -1) {
	FCGIRequest.runServer(fastcgi_port, fs_root_uri);
      }
      else if (ajp_port != -1) {
	Jetty.runJettyServer(-1, ajp_port, fs_root_uri);
      }
      else if (http_port != -1) {
	Jetty.runJettyServer(http_port, -1, fs_root_uri);
      }
      else if (run_shell) {
	ShellRequest sr = new ShellRequest();
	sr.initRequest();

	ESXX.Workload wl = esxx.addRequest(sr, sr, -1 /* no timeout for the shell */);

	try {
	  System.exit((Integer) wl.getResult());
	}
	catch (java.util.concurrent.CancellationException ex) {
	  ex.printStackTrace();
	  System.exit(5);
	}	
      }
      else if (script != null && script.length != 0) {
	File file = new File(script[0]);

	ScriptRequest sr = new ScriptRequest();
	sr.initRequest(file.toURI(), script);
	ESXX.Workload wl = esxx.addRequest(sr, sr, -1 /* no timeout for scripts */);

	try {
	  System.exit((Integer) wl.getResult());
	}
	catch (java.util.concurrent.CancellationException ex) {
	  ex.printStackTrace();
	  System.exit(5);
	}
      }
      else {
	usage(opt, "Required argument missing", 10);
      }
    }
    catch (ParseException ex) {
      usage(opt, ex.getMessage(), 10);
    }
    catch (IOException ex) {
      System.err.println("I/O error: " + ex.getMessage());
      System.exit(20);
    }
    catch (Exception ex) {
      ex.printStackTrace();
      System.exit(20);
    }

    System.exit(0);
  }
}
