package com.kanozz.service;

public class KanoServiceStub implements KanoService{

    private KanoService kanoService;

    public KanoServiceStub(KanoService kanoService){
        this.kanoService = kanoService;
    }


    @Override
    public String kano() {
        return null;
    }

    @Override
    public String kano(String name) {
        return name;
    }
}
