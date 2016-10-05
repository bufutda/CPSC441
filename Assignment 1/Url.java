/**
 * Url class
 *
 * @author Mitchell Sawatzky
 * @version 0.1 Oct 3, 2016
 */

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class Url {
    /**
     * The domain name of the url
     */
    private String basename;

    /**
     * The path name of the url
     */
    private String pathname;

    /**
     * The port
     */
    private int port;

    /**
     * Create a Url from a string representation
     */
    public Url(String address) throws UrlCacheException {
        Pattern validURL = Pattern.compile("^(https?\\:\\/\\/)?([a-z0-9\\-]+\\.)+[a-z]+(\\:[0-9]+)?(\\/[a-z0-9~\\.\\%]*)*((\\?|\\#|\\:).*)?$", Pattern.CASE_INSENSITIVE);
        Matcher m = validURL.matcher(address);
        if (m.matches()) {
            if (Pattern.compile("^https\\:.*$", Pattern.CASE_INSENSITIVE).matcher(address).matches()) {
                port = 443;
            } else {
                port = 80;
            }
            Matcher bn = Pattern.compile("(?<=(^|\\/\\/))([a-z\\-]+\\.)+[a-z]+", Pattern.CASE_INSENSITIVE).matcher(address);
            if (bn.find()) {
                basename = bn.group(0);
                if (bn.end(0) == address.length()) {
                    pathname = "/";
                } else if (address.charAt(bn.end(0)) == ':') {
                    int endPort = address.indexOf('/', bn.end(0) + 1);
                    if (endPort == -1) {
                        pathname = "/";
                        try {
                            port = Integer.parseInt(address.substring(bn.end(0) + 1));
                        } catch (NumberFormatException e) {
                            throw new UrlCacheException("Bad URL port: " + address);
                        }
                    } else {
                        try {
                            port = Integer.parseInt(address.substring(bn.end(0) + 1, endPort));
                        } catch (NumberFormatException e) {
                            throw new UrlCacheException("Bad URL port: " + address);
                        }
                        pathname = address.substring(endPort);
                    }
                } else {
                    pathname = address.substring(bn.end(0));
                }
            } else {
                throw new UrlCacheException("No basename found");
            }

        } else {
            throw new UrlCacheException("Invalid URL: " + address);
        }

    }

    /**
     * Get the basename
     * @returns String basename of the url
     */
    public String getBasename() {
        return basename;
    }

    /**
     * Get the pathname
     * @returns String pathname of the url
     */
    public String getPathname() {
        return pathname;
    }

    /**
     * Get the port
     * @returns Int port
     */
    public int getPort() {
        return port;
    }

    /**
     * Return a string representation of the Url
     * @retuns String the string representation
     */
    public String toString() {
        return basename + ":" + port + pathname;
    }
}
