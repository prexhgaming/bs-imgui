#include <pthread.h>
#include <chrono>
#include <thread>

#define SLEEP_TIME 1000LL / 120LL
/*
struct sRegion{
	uintptr_t start, end;
};

std::vector<sRegion> trapRegions;

bool isObjectInvalid(UObject *obj){
	if (!Tools::IsPtrValid(obj)){
		return true;
	}
	if (!Tools::IsPtrValid(obj->ClassPrivate)){
		return true;
	}
	if (obj->InternalIndex <= 0){
		return true;
	}
	if (obj->NamePrivate.ComparisonIndex <= 0){
		return true;
	}
	if ((uintptr_t)(obj) % sizeof(uintptr_t) != 0x0 && (uintptr_t)(obj) % sizeof(uintptr_t) != 0x4){
		return true;
	}
	if (std::any_of(trapRegions.begin(), trapRegions.end(), [obj](sRegion region) { return ((uintptr_t)obj) >= region.start && ((uintptr_t)obj) <= region.end; }) ||
		std::any_of(trapRegions.begin(), trapRegions.end(), [obj](sRegion region) { return ((uintptr_t)obj->ClassPrivate) >= region.start && ((uintptr_t)obj->ClassPrivate) <= region.end; }))
	{
		return true;
	}
	return false;
}


[[noreturn]] void *THIRD_PARTY_FIXED(void *) {
    while (true) {
        auto startTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        auto objects = UObject::GetGlobalObjects();
        for (int i = 0; i < objects.Num(); i++) {
            auto object = objects.GetByIndex(i);
            if (isObjectInvalid(object))
                continue;

            if (object->IsA(UBulletHitInfoUploadComponent::StaticClass())) {
                auto bulletComponent = (UBulletHitInfoUploadComponent *)object;
                bulletComponent->bEnableTssSdkAntiData = 0;
                bulletComponent->bEnableTssSdkAntiDataFilter = 0;
                bulletComponent->bEnableTssSdkAntiDataFilterNew = 0;
                bulletComponent->bDisableDsTick = 1;
                bulletComponent->bDisableDsAntiDataReport = 1;
                bulletComponent->bEnableAddSPCShootData = 0;
                bulletComponent->NeedSendSimpleCharacterHitData = 0;
            }
        }

        auto elapsedTime = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count() - startTime;

        std::this_thread::sleep_for(std::chrono::milliseconds(std::max(std::min(0LL, SLEEP_TIME - elapsedTime), SLEEP_TIME)));
    }
}

__attribute__((constructor)) void mainload() {
    pthread_t ptid;
    pthread_create(&ptid, 0, THIRD_PARTY_FIXED, 0);
}*/