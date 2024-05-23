package com.youngbryanyu.simplistash.commands.read;

import java.util.Deque;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.youngbryanyu.simplistash.commands.Command;
import com.youngbryanyu.simplistash.protocol.ProtocolUtil;
import com.youngbryanyu.simplistash.stash.Stash;
import com.youngbryanyu.simplistash.stash.StashManager;

/**
 * The INFO command. Gets info about a stash.
 */
@Component
public class InfoCommand implements Command {
    /**
     * The command's name.
     */
    public static final String NAME = "INFO";
    /**
     * The command's format.
     */
    private static final String FORMAT = "INFO <num_opt_args> [NAME]";
    /**
     * The minimum number of required arguments.
     */
    private final int minRequiredArgs;
    /**
     * The stash manager.
     */
    private final StashManager stashManager;

    /**
     * The optional args.
     */
    public enum OptionalArg {
        NAME;    
    }

    /**
     * Constructor for the INFO command.
     * 
     * @param stashManager The stash manager.
     */
    @Autowired
    public InfoCommand(StashManager stashManager) {
        this.stashManager = stashManager;
        minRequiredArgs = ProtocolUtil.getMinRequiredArgs(FORMAT);
    }

    /**
     * Executes the INFO command. Returns null if there aren't enough tokens.
     * 
     * @param tokens   The client's tokens.
     * @param readOnly Whether the client is read-only.
     * @return The response to the client.
     */
    public String execute(Deque<String> tokens, boolean readOnly) {
        /* Check if there are enough tokens */
        if (tokens.size() < minRequiredArgs) {
            return null;
        }

        /* Extract tokens */
        tokens.pollFirst();
        String numOptionalArgsStr = tokens.pollFirst();

        /* Get number of optional args */
        int numOptionalArgs = getNumOptionalArgs(numOptionalArgsStr);
        if (numOptionalArgs == -1) {
            return ProtocolUtil.buildErrorResponse(buildErrorMessage(ErrorCause.INVALID_OPTIONAL_ARGS_COUNT));
        }

        /* Check if there are enough tokens for optional args */
        if (tokens.size() < numOptionalArgs) {
            tokens.addFirst(numOptionalArgsStr);
            tokens.addFirst(NAME);
            return null;
        }

        /* Process optional args */
        Map<String, String> optionalArgVals = processOptionalArgs(tokens, numOptionalArgs);
        if (optionalArgVals == null) {
            return ProtocolUtil.buildErrorResponse(buildErrorMessage(ErrorCause.MALFORMED_OPTIONAL_ARGS));
        }

        /* Get stash name */
        String name;
        if (optionalArgVals.containsKey(OptionalArg.NAME.name())) {
            name = optionalArgVals.get(OptionalArg.NAME.name());
        } else {
            name = StashManager.DEFAULT_STASH_NAME;
        }

        /* Get stash */
        Stash stash = stashManager.getStash(name);
        if (stash == null) {
            return ProtocolUtil.buildErrorResponse(buildErrorMessage(ErrorCause.STASH_DOESNT_EXIST));
        }

        /* Get info */
        String info = stash.getInfo();

        /* Build response */
        return ProtocolUtil.buildValueResponse(info);
    }

    /**
     * Returns the command's name.
     * 
     * @return The command's name.
     */
    public String getName() {
        return NAME;
    }
}
