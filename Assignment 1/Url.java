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
     * The protocol
     */
    private String protocol;

    /**
     * Create a Url from a string representation
     */
    public Url(String address) throws UrlCacheException {
        /* regular expression for URL validation
            ^                          Start of string
            (https?\\:\\/\\/)?        0 or 1 of: "http://"" or "https://"" (protocols)
            ([a-z0-9\\-]+\\.)+        any characters or dashes, followed by 1 dot (subdomains and domain body)
            [a-z]+                    any characters (domain suffix)
            (\\:[0-9]+)?              0 or 1 of: : followed by numbers (port)
            (\\/[a-z0-9~\\.\\%]*)*    0 or N of: URL safe characters (path)
            ((\\?|\\#|\\:).*)?        0 or 1 of: Path-ending tokens and their bodies (querystring, hash)
            $                         End of string
        */
        Pattern validURL = Pattern.compile("^(https?\\:\\/\\/)?([a-z0-9\\-]+\\.)+[a-z]+(\\:[0-9]+)?(\\/[a-z0-9~\\.\\%]*)*((\\?|\\#|\\:).*)?$", Pattern.CASE_INSENSITIVE);

        // if the address passes the regular expression
        Matcher m = validURL.matcher(address);
        if (m.matches()) {
            /* regular expression for protocols
                ^         Start of string
                https\\:  "https:"
                .*        Any amount of any characters
                $         End of string
            */
            if (Pattern.compile("^https\\:.*$", Pattern.CASE_INSENSITIVE).matcher(address).matches()) {
                // "https://" was included, use the default SSL port
                protocol = "HTTPS";
                port = 443;
            } else {
                // "http://" or no protocol was included, use the default port
                protocol = "HTTP";
                port = 80;
            }
            /* regular expression for extracting the basename
                (?<=(^|\\/\\/))        If the next section is preceded by the start of the string,
                                       or "//" (start of the domain name)
                ([0-9a-z\\-]+\\.)+     One or more URL safe characters followed by one dot (subdomains + domain body)
                [a-z]+                 One or more characters (domain suffix)
            */
            Matcher bn = Pattern.compile("(?<=(^|\\/\\/))([0-9a-z\\-]+\\.)+[a-z]+", Pattern.CASE_INSENSITIVE).matcher(address);
            if (bn.find()) {
                // first match
                basename = bn.group(0);

                // no pathname was given
                if (bn.end(0) == address.length()) {
                    pathname = "/";

                // port was given
                } else if (address.charAt(bn.end(0)) == ':') {
                    // address of the end of the port declaration
                    int endPort = address.indexOf('/', bn.end(0) + 1);
                    // if no pathname was given after the port
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
     * Get the protocol
     * @returns String protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Return a string representation of the Url
     * @retuns String the string representation
     */
    public String toString() {
        return basename + ":" + port + pathname;
    }
}
