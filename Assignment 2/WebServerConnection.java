import java.net.Socket;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.Date;
import java.text.SimpleDateFormat;

/**
 * A worker class for WebServer
 * @author Mitchell Sawatzky
 * @version 1.0, October 28, 2016
 */
public class WebServerConnection implements Runnable {
    /**
     * The client socket
     */
    private Socket client;

    /**
     * A basic constructor
     * @param Socket sock - the client socket
     */
    public WebServerConnection (Socket sock) {
        client = sock;
    }

    /**
     * The driving function of this worker class
     */
    public void run () {
        try {
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            DataInputStream in = new DataInputStream(client.getInputStream());
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
            format.setTimeZone(TimeZone.getTimeZone("GMT+0000"));

            HashMap<String, String> headers = new HashMap<>();
            HashMap<String, String> headersOut = new HashMap<>();

            // form the basic outgoing headers
            headersOut.put("Date", format.format(new Date()));
            headersOut.put("Server", "CPSC441/1.0");
            headersOut.put("Connection", "close");
            headersOut.put("Content-Length", "0");

            String requestPath;
            // read the request
            try {
                byte current;
                int i;
                byte[] currentBytes;
                boolean requestLine = true;
                ArrayList<Byte> bytes = new ArrayList<Byte>();
                String request = "";

                // read bytes one at a time
                while ((i = in.read()) != -1) {
                    current = (byte) i;

                    // add the current byte to the past bytes
                    bytes.add(current);

                    // construct a primitive array of bytes from the ArrayList
                    currentBytes = new byte[bytes.size()];
                    for (int j = 0; j < bytes.size(); j++) {
                        currentBytes[j] = bytes.get(j).byteValue();
                    }

                    // interpret the bytes as a string
                    String cur;
                    cur = new String(currentBytes, "UTF-8");

                    // the request headers are done, move on
                    if (cur.equals("\r\n")) {
                        break;
                    }

                    // if the line is over, clear the byte ArrayList (start building a new string)
                    if (cur.endsWith("\r\n")) {
                        if (requestLine) {
                            request = cur.replace("\r\n", "");
                            requestLine = false;
                        } else {
                            cur = cur.replace("\r\n", "");
                            String key = cur.substring(0, cur.indexOf(": "));
                            String value = cur.substring(cur.indexOf(": ") + 2);
                            if (headers.containsKey(key)) {
                                System.out.println("HeaderParsingException: duplicate header: " + key);
                                return;
                            }
                            headers.put(key, value);
                        }
                        bytes.clear();
                    }
                }

                // parse the request line
                String[] req = request.split(" ");
                if (req.length != 3) {
                    throw new Exception("Malformed request line: " + request);
                }
                if (!req[0].equals("GET")) {
                    throw new Exception("Bad method");
                }
                if (!req[2].matches("^HTTP/1\\.[01]$")) {
                    throw new Exception("Bad version");
                }
                String path = parsePath(req[1]);

                // check the path is OK
                // assignment says that the files are all in the current directory,
                // so the path shouldn't have any slashes
                if (path.matches("/")) {
                    endRequest(in, out, formStatusLine(404), headersOut, null);
                    return;
                }
                // check that the file exists
                File file = new File(path);
                if (file.exists() && file.isFile()) {
                    endRequest(in, out, formStatusLine(200), headersOut, file);
                } else {
                    endRequest(in, out, formStatusLine(404), headersOut, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
                endRequest(in, out, formStatusLine(400), headersOut, null);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    /**
     * Write the response and body back to the client, and close all sockets/streams
     * @param DataInputStream in - the input stream to close
     * @param DataOutputStream out - the output stream to write the response to and close
     * @param String statusLine - the first line of the response
     * @param HashMap<String, String> headers - a map of headers to send out
     * @param File file - the file object of the requested object
     */
    private void endRequest(DataInputStream in, DataOutputStream out, String statusLine, HashMap<String, String> headers, File file) {
        try {
            // set final headers
            if (file != null) {
                SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
                format.setTimeZone(TimeZone.getTimeZone("GMT+0000"));

                headers.put("Content-Length", String.valueOf(file.length()));
                headers.put("Last-Modified", format.format(new Date(file.lastModified())));

                // judging by the requested extension, set the content-type. If not known, don't set it
                String contentType = formContentType(file.getPath());
                if (contentType != null) {
                    headers.put("Content-Type", contentType);
                }
            }

            // form header-portion of message
            String top = "";
            top += statusLine + "\r\n";
            Iterator<Map.Entry<String, String>> i = headers.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry<String, String> p = i.next();
                top += p.getKey() + ": " + p.getValue() + "\r\n";
                i.remove();
            }
            top += "\r\n";

            // write header-portion to socket
            out.writeBytes(top);

            if (file != null) {
                // write file to socket
                FileInputStream fis = new FileInputStream(file);
                int pipeByte;
                while ((pipeByte = fis.read()) != -1) {
                    out.write(pipeByte);
                }
                fis.close();
            }

            out.flush();

            // close everything
            in.close();
            out.close();
            client.close();
        } catch (IOException e) {
            System.out.println(e);
            return;
        }
    }

    /**
     * Map a status code to a response line
     * @param int code - the statuscode to map
     * @returns a String representation of the statuscode
     */
    private String formStatusLine (int code) {
        // spoof that this is HTTP/1.0 compliant
        String line = "HTTP/1.0 ";
        switch (code) {
            case 200:
                line += "200 OK";
                break;
            case 400:
                line += "400 Bad Request";
                break;
            case 404:
                line += "404 Not Found";
                break;
            default:
                line += code;
                break;
        }
        return line;
    }

    /**
     * Turn a request path into something that the File class can handle
     * @param String path - the original path in the request body
     * @throws Exception when the path is invalid
     * @returns a File-compliant version of the path
     */
    private String parsePath (String path) throws Exception {
        // check that the path is a good sytax, where
        //     ^                  is the start of the string
        //     (/[a-zA-Z\\.%0-9\\-_]+)+  is 1 or more groups of a slash followed by URL-compliant characters
        //     /?                 is 0 or 1 slash
        //     ((\\?|#|;).*)?     is 1 or 0 groups of ?, #, or ; followed by anything (querystrings, hashes, browser-plugin values, etc)
        //     $                  is the end of the string
        if (!path.matches("^(/[a-zA-Z\\.%0-9\\-_]+)+/?((\\?|#|;).*)?$") && !path.equals("/")) {
            throw new Exception("Bad path: " + path);
        }

        // remove parts of the path that are irrelevant (querystring, hash, etc)
        int endToken = path.length();
        String[] endTokens = {"?", "#", ";"};
        for (int i = 0; i < endTokens.length; i++) {
            int index = path.indexOf(endTokens[i]);
            if (index != -1 && index < endToken) {
                endToken = index;
            }
        }
        if (endToken != 0) {
            path = path.substring(0, endToken);
        }

        // parse escaped characters
        String unescaped = "";
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == 37) {
                // character is %, next 2 chars should be a hex pair
                String hexPair = String.valueOf(path.charAt(++i)) + String.valueOf(path.charAt(++i));
                try {
                    char encoded = (char)Integer.parseInt(hexPair, 16);
                    unescaped += String.valueOf(encoded);
                } catch (NumberFormatException e) {
                    throw new Exception("Malformed URI");
                }
            } else {
                unescaped += String.valueOf(path.charAt(i));
            }
        }

        // handle inferred path
        if (unescaped.endsWith("/")) {
            unescaped += "index.html";
        }

        // remove the first slash
        unescaped = unescaped.substring(1);

        return unescaped;
    }

    /**
     * Map a file extension to a content-type
     * @param String path - the path containing the file extension
     * @returns String content-type or null if none was found
     */
    private String formContentType(String path) {
        int extIndex = path.lastIndexOf('.');
        if (extIndex == -1) {
            return null;
        }
        String ext = path.substring(extIndex + 1);
        switch (ext) {
            case "html":
            case "php":
                return "text/html";
            case "java":
            case "txt":
                return "text/plain";
            case "class":
                return "application/java";
            case "pdf":
                return "application/pdf";
            case "png":
            case "jpg":
            case "gif":
            case "jpeg":
                return "image/" + ext;
            default:
                return null;
        }
    }
}
