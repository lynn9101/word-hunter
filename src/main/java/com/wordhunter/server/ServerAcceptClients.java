package com.wordhunter.server;

import com.wordhunter.conversion.PlayerConversion;
import com.wordhunter.models.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ServerAcceptClients
 * thread to continue listening for new connections
 */
class ServerAcceptClients extends Thread {
    public ServerSocket sock = null;                        // server socket

    private final Vector<String> colorIds = new Vector<>();       // colors assigned to players

    private final Semaphore playerColorListLock = new Semaphore(1);

    private final Vector<Player> disconnectedPlayerList = new Vector<>();

    /**
     * ServerAcceptClients()
     * set up colorIds vector
     */
    public ServerAcceptClients() {
        // setup color vector
        colorIds.add("#8b5dc8"); // purple
        colorIds.add("#5dc866"); // green
        colorIds.add("#c8c15d"); // yellow
        colorIds.add("#00ccff"); // cyan
        colorIds.add("#c87b5d"); // orange
        colorIds.add("#c85daf"); // pink
    }

    /**
     * removePlayer()
     * on client disconnect, update username/colorId lists
     *
     * @param index index in playerList and colorId
     */
    public void removePlayer(int index) {
        try {
            playerColorListLock.acquire();

            // remove from server lists
            try {
                Player removedPlayer = ServerMain.playerList.remove(index);
                colorIds.add(removedPlayer.getColor());
                // store for allowing reconnection attempt
                disconnectedPlayerList.add(removedPlayer);

                // broadcast updated player list and color ids
                ServerMain.broadcast("playerDisconnect" + ServerMain.messageDelimiter
                        + "playerList" + ServerMain.messageDelimiter
                        + ","
                        + PlayerConversion.fromPlayers(ServerMain.playerList)
                        + ServerMain.messageDelimiter);
                playerColorListLock.release();
            }
            catch (ArrayIndexOutOfBoundsException e){
                System.out.println("Game has already ended, no players to remove.");
            }

        } catch (InterruptedException ignored) {
        }
    }

    /**
     * run()
     * start listening for new connections
     */
    public void run() {
        System.out.println("starting accepting clients");

        // create server
        try {
            sock = new ServerSocket(ServerMain.serverPort);
        } catch (IOException e) {
            System.out.println("Failed to start server");
            System.exit(1);
        }

        // start listening for client connection requests
        try {
            ServerMain.serverState = ServerState.LISTENING_CONNECTIONS;
            while (true) {
                // on request -> create new thread with socket
                Socket client = sock.accept();

                // setup read stream
                InputStream is = client.getInputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(is));

                // read and process message from client
                String[] msg = in.readLine().split(ServerMain.messageDelimiter);

                // new client join
                if (Objects.equals(msg[0], "username")) {
                    // game started
                    if (ServerMain.serverState != ServerState.LISTENING_CONNECTIONS) {
                        ServerMain.sendMessageToClient(client, "error" + ServerMain.messageDelimiter
                                + "game already started");
                        in.close();
                        client.close();
                    }
                    // max clients reached
                    else if (ServerMain.clientLimit <= ServerMain.playerList.size()) {
                        ServerMain.sendMessageToClient(client, "error" + ServerMain.messageDelimiter
                                + "maximum clients reached");
                        in.close();
                        client.close();
                    }
                    else {
                        // check if username already taken
                        Optional<Player> foundPlayer = ServerMain.playerList.stream()
                                .filter(player -> player.getName().equals(msg[1]))
                                .findFirst();
                        if(foundPlayer.isPresent())
                        {
                            ServerMain.sendMessageToClient(client, "error" + ServerMain.messageDelimiter
                                    + "username taken");
                            in.close();
                            client.close();
                            continue;
                        }
                        // create player obj and store socket in there
                        newPlayerJoinHandle(msg[1], client);
                    }
                }
            }
        } catch (IOException e) // called when server socket closed from outside thread
        {
            System.out.println("Server listening closed");
        }
    }

    /**
     * newPlayerJoinHandle()
     *
     * @param username new player username
     * @param client   new player socket
     */
    private void newPlayerJoinHandle(String username, Socket client) {
        try {
            playerColorListLock.acquire();

            int playerCount = ServerMain.playerList.size() + 1;
            System.out.println("creating player " + playerCount + ": " + username);

            Player newPlayer = new Player(username, colorIds.remove(0), client);
            // add player to list and create listening thread
            ServerMain.playerList.add(
                    newPlayer
            );

            PlayerThread newPlayerThread = new PlayerThread(this, client, playerCount - 1, newPlayer);
            newPlayerThread.start();

            // broadcast new player list and color ids
            ServerMain.broadcast("newPlayerJoin" + ServerMain.messageDelimiter
                    + "playerList" + ServerMain.messageDelimiter
                    + PlayerConversion.fromPlayers(ServerMain.playerList));

            // send to new client how long until game starts (change to broadcast to make sure all timers are right?)
            long elapsedTimeNs = System.nanoTime() - ServerMain.timerStartTime;
            long remainingSeconds = (ServerMain.startGameTimeMin * 15)
                    - TimeUnit.SECONDS.convert(elapsedTimeNs, TimeUnit.NANOSECONDS);
            ServerMain.sendMessageToClient(client, "startTimer" + ServerMain.messageDelimiter
                    + remainingSeconds);

            playerColorListLock.release();
        } catch (InterruptedException ignored) {
        }
    }
}
