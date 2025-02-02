package com.wordhunter.server;

import com.wordhunter.conversion.PlayerConversion;
import com.wordhunter.conversion.WordConversion;
import com.wordhunter.models.Player;
import com.wordhunter.models.Word;
import com.wordhunter.models.WordGenerator;
import com.wordhunter.models.WordList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;


/**
 * Used to map keywords to functions
 */
interface ServerMessageMethod
{
    void method(PlayerThread parent, String input);
}

class WordTimerTask extends TimerTask {
    private final Word currentWord;

    public WordTimerTask(Word word) {
        this.currentWord = word;
    }
    public void run() {
        // Remove once the TTL of the word is done
        ServerMain.wordsList.set(currentWord.getWordID(), null);
        ServerMain.broadcast("removeWord" + ServerMain.messageDelimiter + currentWord.getWordID() + ServerMain.messageDelimiter + 0);

        Word newWord = WordGenerator.generateNewWord();
        ServerMain.wordsList.set(newWord.getWordID(), newWord);
        ServerMain.broadcast("addNewWord" + ServerMain.messageDelimiter + WordConversion.fromWord(newWord));

        Timer timer = new Timer();
        timer.schedule(new WordTimerTask(newWord), newWord.generateTimeToLive());
    }
}

/**
 * program main entry point
 */
public class ServerMain extends Thread
{
    // const variables used in client too
    public final static int serverPort = 6666;
    public final static String messageDelimiter = "!";
    public final static int clientLimit = 6;
    public final static int startGameTimeMin = 1;
    public final static int gameMaxTimeMin = 1;
    public final static int heartBeatInterval = 5000; // in milliseconds


    // server exclusive variables
    public static final Vector<Player> playerList = new Vector<>();
    public static ServerState serverState = ServerState.STARTED;
    public static long timerStartTime;

    // game variables
    public final static int wordLimit = 15;
    public final static int dimension = 5;
    public static WordList wordsList;
    public final static String defaultColor = "#000000";

    public ServerMain()
    {
        wordsList = new WordList(WordGenerator.dimension);
    }

    /**
     * main()
     * main entry point. creates server, waits x minutes before starting game
     */
    public void run()
    {
        System.out.println("starting server");
        WordGenerator.readDictionary();

        // start thread to start accepting clients
        ServerAcceptClients thread = new ServerAcceptClients();
        thread.start();

        // waiting room timer
        try
        {
            timerStartTime = System.nanoTime();
            Thread.sleep(startGameTimeMin * 15000);
            ServerMain.serverState = ServerState.GAME_IN_PROGRESS;
        }
        catch (InterruptedException e)
        {
            System.out.println("server timer interrupted");
        }

        System.out.println("server game start");


        broadcast("gameStart" + messageDelimiter
                + "gameTimer" + messageDelimiter
                + gameMaxTimeMin * 60);

        // generating words and broadcast each word to all client
        for (int i = 0; i < wordLimit; i++) {
            Word newWord = WordGenerator.generateNewWord();
            wordsList.set(newWord.getWordID(), newWord);
            ServerMain.broadcast("addNewWord" + ServerMain.messageDelimiter + WordConversion.fromWord(newWord));

            Timer timer = new Timer();
            timer.schedule(new WordTimerTask(newWord), newWord.generateTimeToLive());
        }

        // game duration timer
        try
        {
            timerStartTime = System.nanoTime();
            Thread.sleep(gameMaxTimeMin * 60000);
            thread.sock.close(); // stop server from accepting new connections
            ServerMain.serverState = ServerState.GAME_END;
        }
        catch (InterruptedException | IOException e)
        {
            System.out.println("server timer interrupted");
        }

        broadcast("gameOver" + ServerMain.messageDelimiter + PlayerConversion.fromPlayers(ServerMain.playerList));
    }

    /**
     * broadcast()
     * send message to all players
     * @param message message to send to all connected clients
     */
    public static void broadcast(String message)
    {
        // write message to each client socket
        for(Player player: playerList)
        {
            sendMessageToClient(player.getSocket(), message);
        }
    }

    /**
     * sendMessageToClient()
     * send message to only one specific client
     * @param sock socket
     * @param message message
     */
    public static void sendMessageToClient(Socket sock, String message)
    {
        try
        {
            OutputStream os = sock.getOutputStream();
            if(os == null)
            {
                System.out.println("Failed to get client output stream");
                return;
            }
            PrintWriter out = new PrintWriter(os, true);
            out.println(message);
        }
        catch (IOException e)
        {
            System.out.println("Failed to get client output stream");
            e.printStackTrace();
        }
    }
}
