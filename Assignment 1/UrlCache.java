/**
 * UrlCache Class
 *
 * @author 	Majid Ghaderi
 * @author Mitchell Sawatzky
 * @version	1.1, Sep 30, 2016
 * @updated Oct 5, 2016
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class UrlCache {
    /**
     * Catalog for storing objects and their Last-Modified values
     */
    private HashMap<String, Long> catalog;

    /**
     * The location for the object catalog
     */
    private final String catalogPath = "./catalog.ser";

    /**
     * The location for the cache root
     */
    private final String cacheRoot = "./cache";

    /**
     * Set to true to print communications between client and server
     */
    private final boolean printVerbose = false;

    /**
     * Default constructor to initialize data structures used for caching/etc
     * If the cache already exists then load it. If any errors then throw exception.
     *
     * @throws UrlCacheException if encounters any errors/exceptions
     */
    @SuppressWarnings("unchecked")
    public UrlCache() throws UrlCacheException {
        // if the catalog already exists, load it
        if (new File(catalogPath).isFile()) {
            try {
                ObjectInputStream in = new ObjectInputStream(new FileInputStream(catalogPath));
                catalog = (HashMap<String, Long>) in.readObject();
                in.close();
            } catch (FileNotFoundException e) {
                throw new UrlCacheException("Could not read from catalog file: " + catalogPath);
            } catch (IOException e) {
                throw new UrlCacheException("Could not read from catalog file: " + e.toString());
            } catch (ClassNotFoundException e) {
                throw new UrlCacheException("Could not interpret catalog file: " + e.toString());
            }
        } else {
            catalog = new HashMap<String, Long>();
            // write the catalog to the file system
            dumpCache(catalogPath);
        }
    }

    /**
     * Downloads the object specified by the parameter url if the local copy is out of date.
     *
     * @param url	URL of the object to be downloaded. It is a fully qualified URL.
     * @throws UrlCacheException if encounters any errors/exceptions
     */
    public void getObject(String url) throws UrlCacheException {
        Url u = new Url(url);
        Socket sock;
        PrintWriter out;
        DataInputStream in;
        String since;
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
        format.setTimeZone(TimeZone.getTimeZone("GMT+0000"));

        // if the protocol is supported
        if (!u.getProtocol().equals("HTTP")) {
            throw new UrlCacheException("Protocol not supported: " + u.getProtocol());
        }

        // Open socket
        try {
            sock = new Socket(u.getBasename(), u.getPort());
            out = new PrintWriter(new DataOutputStream(sock.getOutputStream()));
            in = new DataInputStream(sock.getInputStream());
        } catch (UnknownHostException e) {
            throw new UrlCacheException("Could not resolve host: " + u.getBasename() + " on port " + u.getPort());
        } catch (IOException e) {
            throw new UrlCacheException(e.toString());
        }

        // Make request
        sendHeader(out, "GET " + u.getPathname() + " HTTP/1.1");
        sendHeader(out, "Host: " + u.getBasename());
        try {
            sendHeader(out, "If-modified-since: " + getLastModified(url));
        } catch (UrlCacheException e) {
            // file not in cache
        }

        // end request
        sendHeader(out);
        out.flush();

        // Parse Headers
        byte current;
        int i;
        long lm = 0L;
        byte[] currentBytes;
        int length = 0;
        int statusCode = -1;
        ArrayList<Byte> bytes = new ArrayList<Byte>();
        try {
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
                try {
                    cur = new String(currentBytes, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new UrlCacheException(e.toString());
                }

                // check for the status
                if (cur.matches("^HTTP/1\\.[01] [0-9]{3}.*$")) {
                    statusCode = Integer.parseInt(cur.split(" ")[1]);
                }

                // check for the Last-Modified header
                if (cur.length() > 14 && cur.substring(0, 15).equals("Last-Modified: ") && cur.endsWith("\r\n")) {
                    try {
                        lm = format.parse((cur.replace("Last-Modified: ", "").trim())).getTime();
                    } catch (ParseException e) {
                        throw new UrlCacheException("Bad field in Last-Modified header: " + cur);
                    }
                }

                // if the line is over, clear the byte ArrayList (start building a new string)
                if (cur.endsWith("\r\n")) {
                    if (printVerbose) {
                        System.out.println("< " + cur.replace("\r\n", "\\r\\n"));
                    }
                    bytes.clear();
                }

                // the response headers are done, next byte starts the body
                if (cur.equals("\r\n")) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UrlCacheException("Cannot read headers from the socket");
        }

        // statusCode was never set
        if (statusCode == -1) {
            throw new UrlCacheException("HTTP response was not complete");
        }

        // handle response
        switch (statusCode) {
            case 304:
                // the catalog says the file is up to date
                System.out.println("Cached object is up to date. " + getFilePath(u));
                break;
            case 200:
                // download new file
                if (lm == 0L) {
                    throw new UrlCacheException("No Last-Modified header in response");
                }

                // open a file corresponding to the url
                FileOutputStream fout;
                try {
                    constructFilePath(getFilePath(u));
                    File file = new File(getFilePath(u));
                    file.createNewFile();
                    fout = new FileOutputStream(file, false);
                } catch (IOException e) {
                    throw new UrlCacheException("The cache file at " + getFilePath(u) + " cannot be updated");
                }

                // write remaining bytes in the response into the file
                try {
                    while ((i = in.read()) != -1) {
                        fout.write(i);
                    }
                } catch (IOException e) {
                    throw new UrlCacheException("File writing failed: " + e.toString());
                }

                // close the file
                try {
                    fout.flush();
                    fout.close();
                } catch (IOException e) {
                    throw new UrlCacheException(e.toString());
                }

                // update the catalog
                catalog.put(url, lm);
                dumpCache(catalogPath);

                System.out.println("Object downloaded to " + getFilePath(u));
                break;
            default:
                // statusCode was not 200 or 304
                throw new UrlCacheException("Response came back with code " + statusCode);
        }

        // Close streams
        try {
            in.close();
            out.close();
            sock.close();
        } catch (IOException e) {
            throw new UrlCacheException(e.toString());
        }
    }

    /**
     * Returns the Last-Modified time associated with the object specified by the parameter url.
     *
     * @param url 	URL of the object
     * @return the Last-Modified time as a date string
     * @throws UrlCacheException if the specified url is not in the cache, or there are other errors/exceptions
     */
    public String getLastModified(String url) throws UrlCacheException {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
        format.setTimeZone(TimeZone.getTimeZone("GMT+0000"));

        // if the object is in the catalog
        if (catalog.containsKey(url)) {
            return format.format(new Date(catalog.get(url)));
        } else {
            throw new UrlCacheException("Object does not exist: " + url);
        }
    }

    /**
     * Dump the catalog to the filesystem for persistence.
     *
     * @param path the path to put the catalog
     * @throws UrlCacheException when an error/exception is encountered
     */
    public void dumpCache(String path) throws UrlCacheException {
        // open file and write the object
        try {
            ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(path));
            out.writeObject(catalog);
            out.close();
        } catch (FileNotFoundException e) {
            throw new UrlCacheException("Cannot dump catalog: bad path: " + path);
        } catch (IOException e) {
            throw new UrlCacheException("Cannot dump catalog: " + e.toString());
        }
    }

    /**
     * Get a cache file path from a Url Object
     * @param u the Url to derive the path from
     * @returns String file path
     */
    public String getFilePath(Url u) {
        return cacheRoot + "/" + u.getBasename() + u.getPathname();
    }

    /**
     * Creates a file/directory if it does not exist
     * @param path the file path
     * @param fod true for file, false for dir
     * @throws UrlCacheException if a file or directory cannot be created.
     */
    public void createFileDirectory(String path, boolean fod) throws UrlCacheException {
        File f = new File(path);
        // if it is supposed to be a file
        if (fod) {
            if (f.isDirectory()) {
                throw new UrlCacheException("Cannot create cache file when a directory exists with the same name");
            } else {
                try {
                    // creates the file if it is not there, does nothing if it is
                    f.createNewFile();
                } catch (IOException e) {
                    throw new UrlCacheException(e.toString());
                }
            }
        } else {
            if (f.isFile()) {
                throw new UrlCacheException("Cannot create a directory when a file exists with the same name");
            } else {
                // creates the directory if it is not there, does nothing if it is
                f.mkdir();
            }
        }
    }

    /**
     * Construct the file path if it does not exist
     * @param path the file path
     * @throws UrlCacheException if a file or directory cannot be created.
     */
    public void constructFilePath(String path) throws UrlCacheException {
        // split the path into parts
        String[] paths = path.split("/");
        // iterate through the parts and make sure the directory/file exists
        for (int i = 0; i < paths.length; i++) {
            createFileDirectory(String.join("/", Arrays.copyOfRange(paths, 0, i + 1)), i == paths.length - 1 ? true : false);
        }
    }

    /**
     * Write a string into a stream
     * @param out the stream
     * @param msg the string
     */
    public void sendHeader(PrintWriter out, String msg) {
        out.println(msg);
        if (printVerbose) {
            System.out.println("> " + msg + "\\r\\n");
        }
    }

    /**
     * Write "\r\n"into a stream
     * @param out the stream
     */
    public void sendHeader(PrintWriter out) {
        out.println();
        if (printVerbose) {
            System.out.println("> \\r\\n");
        }
    }

}
