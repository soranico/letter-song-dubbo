package com.kanozz.service.impl;

import com.kanozz.service.KanoService;

public class KanoServiceImpl implements KanoService {
    @Override
    public String kano() {
        return "hello how are you";
    }

    @Override
    public String kano(String name) {
        return name;
    }
}
