// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    
    // All of these properties are optional, you can safely remove them

    description = "Stream anime from An1me.to with full API integration"
    authors = listOf("YourName")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://an1me.to/favicon.ico"
}
