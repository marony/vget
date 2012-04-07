/**
 *  This file is part of ytd2
 *
 *  ytd2 is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  ytd2 is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  You should have received a copy of the GNU General Public License
 *  along with ytd2.
 *  If not, see <http://www.gnu.org/licenses/>.
 */
package com.google.code.mircle.ytd2;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Vector;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import com.google.code.mircle.ytd2.YTD2.VideoQuality;

/**
 * knoedel@section60:~/YouTube Downloads$ url=`wget --save-cookies
 * savecookies.txt --keep-session-cookies --output-document=-
 * http://www.youtube.com/watch?v=9QFK1cLhytY 2>/dev/null | grep
 * --after-context=6 --max-count=1 yt.preload.start | grep img.src | sed -e
 * 's/img.src =//' -e 's/generate_204/videoplayback/' -e 's/\\\u0026/\&/g' -e
 * 's/\\\//g' -e 's/;//g' -e "s/'//g" -e 's/ //g' -e 's/"//g' ` && wget
 * --load-cookies=savecookies.txt -O videofile.flv ${url} && echo ok || echo nok
 * 
 * works without cookies as well
 * 
 */
public class YouTubeInfo {

    boolean bDEBUG;

    boolean bNODOWNLOAD;

    static int iThreadcount = 0;
    int iThreadNo = YouTubeInfo.iThreadcount++; // every download thread
                                                // get its own number

    final String ssourcecodeurl = "http://";
    final String ssourcecodeuri = "[a-zA-Z0-9%&=\\.]";

    String sURL = null; // main URL (youtube start web page)
    String sTitle = null; // will be used as filename
    String sFilenameResPart = null; // can contain a string that prepends the
                                    // filename
    String sVideoURL = null; // one video web resource
    String s403VideoURL = null; // the video URL which we can use as fallback to
                                // my wget call
    Vector<String> sNextVideoURL = new Vector<String>(); // list of URLs from
                                                         // webpage source
    // CookieStore bcs = null; // contains cookies after first HTTP GET
    boolean bisinterrupted = false; // basically the same as
                                    // Thread.isInterrupted()
    int iRecursionCount = -1; // counted in downloadone() for the 3 webrequest
                              // to one video

    String sContentType = null;
    BufferedReader textreader = null;
    BufferedInputStream binaryreader = null;
    HttpGet httpget = null;
    HttpClient httpclient = null;
    HttpHost proxy = null;
    HttpHost target = null;
    HttpContext localContext = null;
    HttpResponse response = null;
    YTD2Base ytd2;
    VideoQuality max;

    static final int CONNECT_TIMEOUT = 5000;
    static final int READ_TIMEOUT = 5000;

    String input;
    long count = 0;
    long total = 0;
    boolean join = false;
    VideoQuality vq;

    public YouTubeInfo(YTD2Base ytd2, String input, VideoQuality max) {
        this.bDEBUG = false;
        this.ytd2 = ytd2;
        this.input = input;
        this.max = max;
    } // YTDownloadThread()

    boolean downloadone(String sURL, VideoQuality vd) throws Exception {
        boolean rc = false;
        boolean rc204 = false;
        boolean rc302 = false;
        boolean rc403 = false;

        this.iRecursionCount++;

        // stop recursion
        try {
            if (sURL.equals(""))
                return (false);
        } catch (NullPointerException npe) {
            return (false);
        }
        if (ytd2.getbQuitrequested())
            return (false); // try to get information about application shutdown

        // TODO GUI option for proxy?

        // http://www.youtube.com/watch?v=Mt7zsortIXs&feature=related 1080p !!
        // "Lady Java" is cool, Oracle is not .. hopefully OpenOffice and Java
        // stay open and free

        // http://www.youtube.com/watch?v=WowZLe95WDY&feature=related Tom Petty
        // And the Heartbreakers - Learning to Fly (wih lyrics)
        // http://www.youtube.com/watch?v=86OfBExGSE0&feature=related URZ 720p
        // http://www.youtube.com/watch?v=cNOP2t9FObw Blade 360 - 480
        // http://www.youtube.com/watch?v=HvQBrM_i8bU MZ 1000 Street Fighter

        // http://wiki.squid-cache.org/ConfigExamples/DynamicContent/YouTube
        // using local squid to save download time for tests

        try {
            // determine http_proxy environment variable
            if (!this.getProxy().equals("")) {

                String sproxy = YTD2.sproxy.toLowerCase().replaceFirst("http://", "");
                this.proxy = new HttpHost(sproxy.replaceFirst(":(.*)", ""), Integer.parseInt(sproxy.replaceFirst(
                        "(.*):", "")), "http");

                SchemeRegistry supportedSchemes = new SchemeRegistry();
                supportedSchemes.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
                supportedSchemes.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));

                HttpParams params = new BasicHttpParams();
                HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
                HttpProtocolParams.setContentCharset(params, "UTF-8");
                HttpProtocolParams.setUseExpectContinue(params, true);

                HttpConnectionParams.setConnectionTimeout(params, CONNECT_TIMEOUT);
                HttpConnectionParams.setSoTimeout(params, READ_TIMEOUT);

                ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, supportedSchemes);

                // with proxy
                this.httpclient = new DefaultHttpClient(ccm, params);
                this.httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, this.proxy);
                this.httpclient.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BEST_MATCH);
            } else {
                // without proxy

                HttpParams params = new BasicHttpParams();
                HttpConnectionParams.setConnectionTimeout(params, CONNECT_TIMEOUT);
                HttpConnectionParams.setSoTimeout(params, READ_TIMEOUT);

                this.httpclient = new DefaultHttpClient(params);
                this.httpclient.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BEST_MATCH);
            }
            this.httpget = new HttpGet(getURI(sURL));
            this.target = new HttpHost(getHost(sURL), 80, "http");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // we dont need cookies at all because the download runs even without it
        // (like my wget does) - in fact it blocks downloading videos from
        // different webpages, because we do not handle the bcs for every URL
        // (downloading of one video with different resolutions does work)
        /*
         * this.localContext = new BasicHttpContext(); if (this.bcs == null)
         * this.bcs = new BasicCookieStore(); // make cookies persistent,
         * otherwise they would be stored in a HttpContext but get lost after
         * calling
         * org.apache.http.impl.client.AbstractHttpClient.execute(HttpHost
         * target, HttpRequest request, HttpContext context)
         * ((DefaultHttpClient) httpclient).setCookieStore(this.bcs); // cast to
         * AbstractHttpclient would be best match because DefaultHttpClass is a
         * subclass of AbstractHttpClient
         */

        // TODO maybe we save the video IDs+res that were downloaded to avoid
        // downloading the same video again?

        this.response = this.httpclient.execute(this.target, this.httpget, this.localContext);

        try {
            // for (int i = 0; i < response.getAllHeaders().length; i++) {
            // debugoutput(response.getAllHeaders()[i].getName().concat("=").concat(response.getAllHeaders()[i].getValue()));
            // }
            // TODO youtube sends a "HTTP/1.1 303 See Other" response if you try
            // to open a webpage that does not exist

            // the second request of a browser is with an URL containing
            // generate_204 which leads to an HTTP response code of (guess) 204!
            // the next query is the same URL with videoplayback instead of
            // generate_204 which leads to an HTTP response code of (guess
            // again) .. no not 200! but 302 and in that response header there
            // is a field Location with a different (host) which we can now
            // request with HTTP GET and then we get a response of (guess :) yes
            // .. 200 and the video resource in the body - whatever the
            // girlsnboys at google had in mind developing this ping pong -
            // we'll never now.
            // but because all nessesary URLs are provided in the source code we
            // dont have to do the same requests as web-browsers do
            // abort if HTTP response code is != 200, != 302 and !=204 - wrong
            // URL?
            // make one exception for 403 - switch to old method of videplayback
            // instead of generate_204
            if (!(rc = this.response.getStatusLine().toString().toLowerCase().matches("^(http)(.*)200(.*)"))
                    & !(rc204 = this.response.getStatusLine().toString().toLowerCase().matches("^(http)(.*)204(.*)"))
                    & !(rc302 = this.response.getStatusLine().toString().toLowerCase().matches("^(http)(.*)302(.*)"))
                    & !(rc403 = this.response.getStatusLine().toString().toLowerCase().matches("^(http)(.*)403(.*)"))) {
                return (rc & rc204 & rc302);
            }
            if (rc204) {
                rc = downloadone(this.sNextVideoURL.get(0), vd);
                return (rc);
            }
            if (rc403) {
                this.sFilenameResPart = null;
                rc = downloadone(this.s403VideoURL, vd);
            }
        } catch (NullPointerException npe) {
            // if an IllegalStateException was catched while calling
            // httpclient.execute(httpget) a NPE is caught here because
            // response.getStatusLine() == null
            this.sVideoURL = null;
        }

        HttpEntity entity = null;
        try {
            entity = this.response.getEntity();
        } catch (NullPointerException npe) {
        }

        // try to read HTTP response body
        if (entity != null) {
            {
                if (this.response.getFirstHeader("Content-Type").getValue().toLowerCase().matches("^text/html(.*)"))
                    this.textreader = new BufferedReader(new InputStreamReader(entity.getContent(),
                            EntityUtils.getContentCharSet(entity)));
                else
                    this.binaryreader = new BufferedInputStream(entity.getContent());
            }

            {
                // test if we got a webpage
                this.sContentType = this.response.getFirstHeader("Content-Type").getValue().toLowerCase();
                if (this.sContentType.matches("^text/html(.*)")) {
                    savetextdata(vd);
                    // test if we got the binary content
                } else if (this.sContentType.matches("video/(.)*")) {
                    reportheaderinfo();
                    this.sVideoURL= null;
                } else { // content-type is not video/
                    rc = false;
                    this.sVideoURL = null;
                }
            }
        } // if (entity != null)

        this.httpclient.getConnectionManager().shutdown();

        try {
            rc = downloadone(this.sVideoURL, vd);
            this.sVideoURL = null;
        } catch (NullPointerException npe) {
        }

        return (rc);

    } // downloadone()

    void reportheaderinfo() {
        this.sVideoURL = null;
    } // reportheaderinfo()

    boolean addVideo(String s) {
        if (s != null) {
            sNextVideoURL.add(s);
            return true;
        }

        return false;
    }

    void savetextdata(VideoQuality vd) throws IOException {
        // read lines one by one and search for video URL
        String sline = "";
        while (sline != null) {
            sline = this.textreader.readLine();
            try {
                if (this.iRecursionCount == 0 && sline.matches("(.*)generate_204(.*)")) {
                    sline = sline.replaceFirst("img.src = '?", ""); // debugoutput("URL: ".concat(sline));
                    sline = sline.replaceFirst("';", ""); // debugoutput("URL: ".concat(sline));
                    sline = sline.replaceFirst("\\u0026", "&"); // debugoutput("URL: ".concat(sline));
                    sline = sline.replaceAll("\\\\", ""); // debugoutput("URL: ".concat(sline));
                    sline = sline.replaceAll("\\s", "");
                    this.s403VideoURL = sline.replaceFirst("generate_204", "videoplayback"); // debugoutput("URL: ".concat(sline));
                                                                                             // //
                                                                                             // this
                                                                                             // is
                                                                                             // what
                                                                                             // my
                                                                                             // wget
                                                                                             // command
                                                                                             // does
                    this.sVideoURL = sline;
                }
                // 2011-03-08 - source code changed from "var swfHTML" to
                // "var swfConfig"
                // 2011-07-30 - source code changed from "var swfConfig"
                // something else .. we now use fmt_url_map as there are the
                // URLs to vidoes with formatstrings
                // 2011-08-20 - source code changed from "fmt_url_map": to
                // "url_encoded_fmt_stream_map":
                if (this.iRecursionCount == 0 && sline.matches("(.*)\"url_encoded_fmt_stream_map\":(.*)")) {

                    HashMap<String, String> ssourcecodevideourls = new HashMap<String, String>();

                    // by anonymous
                    sline = sline.replaceFirst(".*\"url_encoded_fmt_stream_map\": \"", "").replaceFirst("\".*", "")
                            .replace("%25", "%").replace("\\u0026", "&").replace("\\", "");

                    String[] urlStrings = sline.split(",");

                    for (String urlString : urlStrings) {
                        String[] fmtUrlPair = urlString.split("&itag="); // 2011-08-20
                                                                         // \\|
                        fmtUrlPair[0] = fmtUrlPair[0].replaceFirst("url=http%3A%2F%2F", "http://"); // 2011-08-20
                                                                                                    // key-value
                                                                                                    // exchanged
                        fmtUrlPair[0] = fmtUrlPair[0].replaceAll("%3F", "?").replaceAll("%2F", "/")
                                .replaceAll("%3D", "=").replaceAll("%26", "&");
                        fmtUrlPair[0] = fmtUrlPair[0].replaceFirst("&quality=.*", "");
                        try {
                            ssourcecodevideourls.put(fmtUrlPair[1], fmtUrlPair[0]); // save
                                                                                    // that
                                                                                    // URL
                            // TODO add unknown resolutions (43-45,84?)
                        } catch (java.lang.ArrayIndexOutOfBoundsException aioobe) {
                            // TODO there is a new problem with itag=84 (not
                            // &itag=84)
                        }
                    } // for

                    // figure out what resolution-button is pressed now and fill
                    // list with possible URLs
                    switch (vd) {
                    case p1080:
                        // 37|22 - better quality first
                        if (this.addVideo(ssourcecodevideourls.get("37"))) {
                            vq = VideoQuality.p1080;
                            break;
                        }
                    case p720:
                        if (this.addVideo(ssourcecodevideourls.get("22"))) {
                            vq = VideoQuality.p720;
                            break;
                        }
                    case p480:
                        // 35|34
                        if (this.addVideo(ssourcecodevideourls.get("35"))) {
                            vq = VideoQuality.p480;
                            break;
                        }
                    case p360:
                        if (this.addVideo(ssourcecodevideourls.get("34"))) {
                            vq = VideoQuality.p360;
                            break;
                        }
                    case p240:
                        // 18|5
                        if (this.addVideo(ssourcecodevideourls.get("18"))) {
                            vq = VideoQuality.p240;
                            break;
                        }
                    case p120:
                        if (this.addVideo(ssourcecodevideourls.get("5"))) {
                            vq = VideoQuality.p120;
                            break;
                        }
                        break;
                    default:
                        this.sNextVideoURL = null;
                        this.sVideoURL = null;
                        this.sFilenameResPart = null;
                        break;
                    }

                    // remove null entries in list - we later try to download
                    // the first (index 0) and if it fails the next one (at
                    // index 1) and so on
                    for (int x = this.sNextVideoURL.size() - 1; x >= 0; x--) {
                        if (this.sNextVideoURL.get(x) == null)
                            this.sNextVideoURL.remove(x);
                    }

                    // 2011-03-08 new - skip generate_204
                    this.sVideoURL = this.sNextVideoURL.get(0);
                }
                // TODO exchange HTML characters to UTF-8 =
                // http://sourceforge.net/projects/htmlparser/
                if (this.iRecursionCount == 0 && sline.matches("(.*)<meta name=\"title\" content=(.*)")) {
                    String name = sline.replaceFirst("<meta name=\"title\" content=", "").trim();
                    name = StringUtils.strip(name, "\">");
                    name = StringEscapeUtils.unescapeHtml4(name);
                    this.setTitle(name);
                }
            } catch (NullPointerException npe) {
            }
        } // while
    } // savetextdata()

    String getProxy() {
        String sproxy = YTD2.sproxy;
        if (sproxy == null)
            return ("");
        else
            return (sproxy);
    } // getProxy()

    String getURI(String sURL) {
        String suri = "/".concat(sURL.replaceFirst(YTD2.szYTHOSTREGEX, ""));
        return (suri);
    } // getURI

    String getHost(String sURL) {
        String shost = sURL.replaceFirst(YTD2.szYTHOSTREGEX, "");
        shost = sURL.substring(0, sURL.length() - shost.length());
        shost = shost.toLowerCase().replaceFirst("http://", "").replaceAll("/", "");
        return (shost);
    } // gethost

    String getTitle() {
        if (this.sTitle != null)
            return this.sTitle;
        else
            return ("");

    }

    void setTitle(String sTitle) {
        this.sTitle = sTitle;
    }

    String getMyName() {
        return this.getClass().getName().concat(Integer.toString(this.iThreadNo));
    } // getMyName()

    public void setbDEBUG(boolean bDEBUG) {
        this.bDEBUG = bDEBUG;
    } // setbDEBUG

    public void extract() {
        try {
            // TODO check what kind of website the URL is from - this class
            // can only handle YouTube-URLs ... we add other video sources
            // later
            this.sURL = input;

            this.bNODOWNLOAD = ytd2.getbNODOWNLOAD(); // copy ndl-state
                                                      // because this thread
                                                      // should end with a
                                                      // complete file (and
                                                      // report so) even if
                                                      // someone switches to
                                                      // nodl before this
                                                      // thread is finished

            // download one webresource and show result
            downloadone(this.sURL, max);
            this.iRecursionCount = -1;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        join = true;

        ytd2.changed();
    } // run()

} // class YTDownloadThread