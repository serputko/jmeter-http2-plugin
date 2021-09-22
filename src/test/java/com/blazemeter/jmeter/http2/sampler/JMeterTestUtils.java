package com.blazemeter.jmeter.http2.sampler;

import java.io.File;
import kg.apc.emulators.TestJMeterUtils;
import org.apache.http.client.config.CookieSpecs;
import org.apache.jmeter.protocol.http.control.HC4CookieHandler;
import org.apache.jmeter.util.JMeterUtils;

public class JMeterTestUtils {

  private static boolean jeerEnvironmentInitialized = false;
  private static String filePrefix;

  public JMeterTestUtils() {
  }

  public static void setupJmeterEnv() {
    if (!jeerEnvironmentInitialized) {
      jeerEnvironmentInitialized = true;
      TestJMeterUtils.createJmeterEnv();
      JMeterUtils.setProperty("HTTPResponse.parsers", "htmlParser wmlParser cssParser");
      JMeterUtils.setProperty("htmlParser.className",
          "org.apache.jmeter.protocol.http.parser.LagartoBasedHtmlParser");
      JMeterUtils.setProperty("htmlParser.types",
          "text/html application/xhtml+xml application/xml text/xml");
      JMeterUtils.setProperty("wmlParser.className",
          "org.apache.jmeter.protocol.http.parser.RegexpHTMLParser");
      JMeterUtils.setProperty("wmlParser.types", "text/vnd.wap.wml");
      JMeterUtils
          .setProperty("cssParser.className", "org.apache.jmeter.protocol.http.parser.CssParser");
      JMeterUtils.setProperty("cssParser.types", "text/css");
    }
  }
}
