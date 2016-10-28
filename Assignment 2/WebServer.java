import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.io.IOException;

/**
 * A simple WebServer class for instantiating a bare-bones webserver
 * @author Mitchell Sawatzky
 * @version 1.0, October 28, 2016
 */
public class WebServer extends Thread implements Runnable {
    /**
     * Flag set to exit the server accept loop
     */
    private boolean acceptRequests;

    /**
     * Thread pool for workers to execute in
     */
    private ExecutorService executor;

    /**
     * Port to accept connections on
     */
    private int port;

    /**
     * Default constructor
     * @param int port - the network port to bind the created webserver to
     */
    public WebServer (int port) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        executor = Executors.newFixedThreadPool(availableProcessors);
        this.port = port;

        // enable the start method
        acceptRequests = true;
    }

    /**
     * The accept-loop. Since this class extends thread, it will run in a
     * separate, non-blocking thread to the main thread
     */
    public void run () {
        try {
            // open the socket
            ServerSocket sock = new ServerSocket(port);
            sock.setSoTimeout(1000);
            while (acceptRequests) {
                try {
                    Socket cli = sock.accept();

                    // spawn new worker thread
                    executor.execute(new WebServerConnection(cli));
                } catch (SocketTimeoutException e) {
                    // Check while flag
                }
            }
            sock.close();
        } catch (IOException e) {
            System.out.println(e);
            System.out.println("Stopping server...");
            acceptRequests = false;
            shutdown();
        }
    }

    /**
     * Destroy the Webserver, kill the workers
     */
    public void shutdown () {
        // cause the while loop to terminate
        acceptRequests = false;

        // wait for workers to terminate
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                // kill workers
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            // kill workers
            executor.shutdownNow();
        }
    }
}
