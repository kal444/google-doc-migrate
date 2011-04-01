package com.yellowaxe.gdata.gdoc;

import com.beust.jcommander.Parameter;

public class CommandArgs {

    @Parameter(names = "-ou", description = "Origin Google Account's Username", required = true)
    public String origUsername;

    @Parameter(names = "-op", description = "Origin Google Account's Password", required = true)
    public String origPassword;

    @Parameter(names = "-du", description = "Destination Google Account's Username", required = true)
    public String destUsername;

    @Parameter(names = "-dp", description = "Destination Google Account's Password", required = true)
    public String destPassword;

    @Parameter(names = "-t", description = "Test Only - does not perform any actions")
    public boolean testOnly;
}
