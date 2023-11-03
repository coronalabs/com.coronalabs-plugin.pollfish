--
--  main.lua
--  Pollfish Sample App
--
--  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
--

local pollfish = require( "plugin.pollfish" )
local widget = require( "widget" )
local json = require("json")

--------------------------------------------------------------
-- Basic UI setup
--------------------------------------------------------------

display.setStatusBar( display.HiddenStatusBar )
display.setDefault( "background", 0.95, 0.98, 1.0 )

local pollfishLogo = display.newImage("Pollfish.png")
pollfishLogo.anchorY = 0
pollfishLogo.x, pollfishLogo.y = display.contentCenterX, 0
pollfishLogo:scale(0.5, 0.5)

local subTitle = display.newText {
    text = "plugin for Corona SDK",
    x = display.contentCenterX,
    y = 85,
    font = display.systemFont,
    fontSize = 20
}
subTitle:setTextColor(214/255,75/255,71/255)

local processEventTable = function(event)
  local logString = json.prettify(event):gsub("\\","")
  logString = "\nPHASE: "..event.phase.." - - - - - - - - - - - -\n" .. logString
  print(logString)
end

--------------------------------------------------------------
-- Plugin implementation
--------------------------------------------------------------

local pollfishButton
local hidePollfishButton

local pollfishListener = function(event)
    processEventTable(event)

    if (event.phase == "init") then
        pollfish.setUserDetails({customData={myid=32, address="baker street"}})
    elseif (event.phase == "loaded") then
        pollfishButton:setLabel("Show")
    elseif (event.phase == "completed") then
        pollfishButton:setLabel("Load")
    elseif (event.phase == "closed") then
        if (pollfish.isLoaded()) then
            pollfishButton:setLabel("Show")
        else
            pollfishButton:setLabel("Load")
        end
    end
end

local apiKey = system.getInfo( "platformName" ) == "Android" and "5809e959-25bd-4d98-be9c-50d7ce9cb2b0" or "c00e4bca-e281-4c43-a5bb-335f8a22f28d"
print( "Using " .. apiKey )

pollfish.init(pollfishListener, {
    apiKey = apiKey,
    developerMode = true
})

pollfishButton = widget.newButton {
    label = "Load",
    onRelease = function(event)
        if (not pollfish.isLoaded()) then
            print("loading...")
            pollfish.load({customMode=true})
        else
            print("showing...")
            pollfish.show()
        end
    end
}

pollfishButton.x = display.contentCenterX;
pollfishButton.y = display.contentCenterY + pollfishButton.contentHeight * 0.5;
